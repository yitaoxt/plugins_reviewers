// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewers;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.gerrit.common.EventListener;
import com.google.gerrit.common.errors.NoSuchGroupException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountByEmailCache;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.GroupMembers;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.group.GroupsCollection;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.Predicate;
import com.google.gerrit.server.query.QueryParseException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Singleton
class ChangeEventListener implements EventListener {
  private static final Logger log = LoggerFactory
      .getLogger(ChangeEventListener.class);

  private final AccountResolver accountResolver;
  private final AccountByEmailCache byEmailCache;
  private final Provider<GroupsCollection> groupsCollection;
  private final GroupMembers.Factory groupMembersFactory;
  private final DefaultReviewers.Factory reviewersFactory;
  private final GitRepositoryManager repoManager;
  private final WorkQueue workQueue;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ThreadLocalRequestContext tl;
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final ChangeData.Factory changeDataFactory;
  private final ReviewersConfig.Factory configFactory;
  private final Provider<CurrentUser> user;
  private final ChangeQueryBuilder queryBuilder;

  @Inject
  ChangeEventListener(
      final AccountResolver accountResolver,
      final AccountByEmailCache byEmailCache,
      final Provider<GroupsCollection> groupsCollection,
      final GroupMembers.Factory groupMembersFactory,
      final DefaultReviewers.Factory reviewersFactory,
      final GitRepositoryManager repoManager,
      final WorkQueue workQueue,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final ThreadLocalRequestContext tl,
      final SchemaFactory<ReviewDb> schemaFactory,
      final ChangeData.Factory changeDataFactory,
      final ReviewersConfig.Factory configFactory,
      final Provider<CurrentUser> user,
      final ChangeQueryBuilder queryBuilder) {
    this.accountResolver = accountResolver;
    this.byEmailCache = byEmailCache;
    this.groupsCollection = groupsCollection;
    this.groupMembersFactory = groupMembersFactory;
    this.reviewersFactory = reviewersFactory;
    this.repoManager = repoManager;
    this.workQueue = workQueue;
    this.identifiedUserFactory = identifiedUserFactory;
    this.tl = tl;
    this.schemaFactory = schemaFactory;
    this.changeDataFactory = changeDataFactory;
    this.configFactory = configFactory;
    this.user = user;
    this.queryBuilder = queryBuilder;
  }

  @Override
  public void onEvent(Event event) {
    if (!(event instanceof PatchSetCreatedEvent)) {
      return;
    }
    PatchSetCreatedEvent e = (PatchSetCreatedEvent) event;
    ChangeAttribute c = e.change.get();
    Project.NameKey projectName = new Project.NameKey(c.project);
    // TODO(davido): we have to cache per project configuration
    ReviewersConfig config = configFactory.create(projectName);
    List<ReviewerFilterSection> sections = config.getReviewerFilterSections();

    if (sections.isEmpty()) {
      return;
    }

    try (Repository git = repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(git);
        ReviewDb reviewDb = schemaFactory.open()) {
      ChangeData changeData = changeDataFactory.create(
          reviewDb, projectName, new Change.Id(Integer.parseInt(c.number)));
      Set<String> reviewers = findReviewers(sections, changeData);
      if (reviewers.isEmpty()) {
        return;
      }

      final Change change = changeData.change();
      final Runnable task = reviewersFactory.create(change,
          toAccounts(reviewDb, reviewers, projectName,
              e.uploader.get().email));

      workQueue.getDefaultQueue().submit(new Runnable() {
        ReviewDb db = null;

        @Override
        public void run() {
          RequestContext old = tl.setContext(new RequestContext() {

            @Override
            public CurrentUser getUser() {
              return identifiedUserFactory.create(change.getOwner());
            }

            @Override
            public Provider<ReviewDb> getReviewDbProvider() {
              return new Provider<ReviewDb>() {
                @Override
                public ReviewDb get() {
                  if (db == null) {
                    try {
                      db = schemaFactory.open();
                    } catch (OrmException e) {
                      throw new ProvisionException("Cannot open ReviewDb", e);
                    }
                  }
                  return db;
                }
              };
            }
          });
          try {
            task.run();
          } finally {
            tl.setContext(old);
            if (db != null) {
              db.close();
              db = null;
            }
          }
        }
      });
    } catch (OrmException | IOException | QueryParseException x) {
      log.error(x.getMessage(), x);
    }
  }

  private Set<String> findReviewers(List<ReviewerFilterSection> sections,
      ChangeData changeData) throws OrmException, QueryParseException {
    ImmutableSet.Builder<String> reviewers = ImmutableSet.builder();
    List<ReviewerFilterSection> found = findReviewerSections(sections,
        changeData);
    for (ReviewerFilterSection s : found) {
      reviewers.addAll(s.getReviewers());
    }
    return reviewers.build();
  }

  private List<ReviewerFilterSection> findReviewerSections(
      List<ReviewerFilterSection> sections, ChangeData changeData)
          throws OrmException, QueryParseException {
    ImmutableList.Builder<ReviewerFilterSection> found = ImmutableList.builder();
    for (ReviewerFilterSection s : sections) {
      if (Strings.isNullOrEmpty(s.getFilter())
          || s.getFilter().equals("*")) {
        found.add(s);
      } else if (filterMatch(s.getFilter(), changeData)) {
        found.add(s);
      }
    }
    return found.build();
  }

  boolean filterMatch(String filter, ChangeData changeData)
      throws OrmException, QueryParseException {
    Preconditions.checkNotNull(filter);
    ChangeQueryBuilder qb = queryBuilder.asUser(user.get());
    Predicate<ChangeData> filterPredicate = qb.parse(filter);
    // TODO(davido): check that the potential review can see this change
    // by adding AND is_visible() predicate? Or is it OK to assume
    // that reviewers always can see it?
    return filterPredicate.asMatchable().match(changeData);
  }

  private Set<Account> toAccounts(ReviewDb reviewDb, Set<String> in,
      Project.NameKey p, String uploaderEMail) {
    Set<Account> reviewers = Sets.newHashSetWithExpectedSize(in.size());
    GroupMembers groupMembers = null;
    for (String r : in) {
      try {
        Account account = accountResolver.find(reviewDb, r);
        if (account != null) {
          reviewers.add(account);
          continue;
        }
      } catch (OrmException e) {
        // If the account doesn't exist, find() will return null.  We only
        // get here if something went wrong accessing the database
        log.error("Failed to resolve account " + r, e);
        continue;
      }
      if (groupMembers == null) {
        groupMembers =
            groupMembersFactory.create(identifiedUserFactory.create(Iterables
                .getOnlyElement(byEmailCache.get(uploaderEMail))));
      }
      try {
        reviewers.addAll(groupMembers.listAccounts(
            groupsCollection.get().parse(r).getGroupUUID(), p));
      } catch (UnprocessableEntityException | NoSuchGroupException e) {
        log.warn(String.format(
            "Reviewer %s is neither an account nor a group", r));
      } catch (NoSuchProjectException e) {
        log.warn(String.format(
            "Failed to list accounts for group %s and project %s", r, p));
      } catch (IOException | OrmException e) {
        log.warn(String.format("Failed to list accounts for group %s", r), e);
      }
    }
    return reviewers;
  }
}
