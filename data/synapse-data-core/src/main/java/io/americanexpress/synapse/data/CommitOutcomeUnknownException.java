/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.data;

import org.springframework.transaction.TransactionSystemException;

/**
 * A store's transaction manager lost its connection while committing, so whether the transaction committed is
 * unknown: the database may have applied the COMMIT and lost only its reply. The store's transaction managers
 * ({@code SynapseJdbcTransactionManager}, {@code SynapseJpaTransactionManager}) throw it, and an operation's caller
 * gets {@link DataErrors#COMMIT_OUTCOME_UNKNOWN}. Code that commits outside an operation, with a
 * {@code TransactionTemplate} for example, catches it to tell an unknown outcome from other failures: it must not
 * retry the write blindly, but check whether it was applied.
 * <p>
 * It is a {@code TransactionSystemException}, so Spring's commit handling neither rolls back nor reports a rollback
 * failure in its place: the transaction completes with an unknown status. Its cause is the commit's own failure.
 */
public class CommitOutcomeUnknownException extends TransactionSystemException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param commitFailure the commit's failure, which says the connection was lost
     */
    public CommitOutcomeUnknownException(Throwable commitFailure) {
        super("The connection was lost while the transaction committed: whether it committed is unknown",
                commitFailure);
    }
}
