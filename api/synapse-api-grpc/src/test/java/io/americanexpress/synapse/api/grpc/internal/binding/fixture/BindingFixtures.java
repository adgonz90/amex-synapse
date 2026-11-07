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
package io.americanexpress.synapse.api.grpc.internal.binding.fixture;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Operations and records the gRPC binding tests bind to {@code catalog.proto}'s services.
 */
public final class BindingFixtures {

    private BindingFixtures() {
    }

    /** The input of {@code GetItem}. */
    public record GetItem(String id) {
    }

    /** An item. */
    public record ItemRecord(String id, String name, AuthorRecord author) {
    }

    /** An item's author, nested in {@link ItemRecord}. */
    public record AuthorRecord(String name, String countryCode) {
    }

    /** The input of {@code CreateItem}: the item and a value the binding takes from the request context. */
    public record CreateItem(ItemRecord item, String channel) {
    }

    /** A record that is neither an input nor an output of any fixture operation. */
    public record Unrelated(String value) {
    }

    /** An input with a {@code PageQuery} component. */
    public record ListItems(String shelf, PageQuery page) {
    }

    /** An input whose records are reached through an array and a map. */
    @SuppressWarnings("ArrayRecordComponent")
    public record Shelve(String shelf, AuthorRecord[] authors, Map<String, ItemRecord> items) {
    }

    /** A persistence entity. */
    @Entity
    public static class ItemEntity {

        @Id
        private String id = "";

        /**
         * Returns the id.
         *
         * @return the id
         */
        public String getId() {
            return id;
        }
    }

    /** The fixture's error codes. */
    public enum ItemErrors implements ErrorDetails {

        /** No item with the id. */
        ITEM_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "Item was not found";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    /** Finds an item; empty when there is none. */
    public static class GetItemOperation implements QueryOperation<GetItem, Optional<ItemRecord>> {

        @Override
        public Optional<ItemRecord> execute(GetItem input) {
            return Optional.empty();
        }
    }

    /** Creates an item. */
    public static class CreateItemOperation implements CommandOperation<CreateItem, ItemRecord> {

        @Override
        public ItemRecord execute(CreateItem input) {
            return input.item();
        }
    }

    /** Deletes an item. */
    public static class DeleteItemOperation implements CommandOperation<GetItem, Void> {

        @Override
        public Void execute(GetItem input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes and returns nothing. */
    public static class PingOperation implements QueryOperation<Void, Void> {

        @Override
        public Void execute(Void input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Returns an item that always exists (not an {@code Optional}). */
    public static class LoadItemOperation implements QueryOperation<GetItem, ItemRecord> {

        @Override
        public ItemRecord execute(GetItem input) {
            return new ItemRecord(input.id(), "n", new AuthorRecord("a", "US"));
        }
    }

    /** Lists an item's tags. */
    public static class ListTagsOperation implements QueryOperation<GetItem, List<String>> {

        @Override
        public List<String> execute(GetItem input) {
            return List.of();
        }
    }

    /** Takes a {@code PageQuery} inside its input. */
    public static class ListItemsOperation implements QueryOperation<ListItems, List<ItemRecord>> {

        @Override
        public List<ItemRecord> execute(ListItems input) {
            return List.of();
        }
    }

    /** Returns a page. */
    public static class PageItemsOperation implements QueryOperation<GetItem, PageResult<ItemRecord>> {

        @Override
        public PageResult<ItemRecord> execute(GetItem input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Takes records inside an array and a map. */
    public static class ShelveOperation implements CommandOperation<Shelve, Void> {

        @Override
        public Void execute(Shelve input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Returns a persistence entity. */
    public static class FindEntityOperation implements QueryOperation<GetItem, ItemEntity> {

        @Override
        public ItemEntity execute(GetItem input) {
            return new ItemEntity();
        }
    }

    /** Takes a plain string, which is not a record. */
    public static class EchoOperation implements QueryOperation<String, ItemRecord> {

        @Override
        public ItemRecord execute(String input) {
            return new ItemRecord(input, "n", new AuthorRecord("a", "US"));
        }
    }

    /** Bound to the streaming {@code WatchItems} method. */
    public static class WatchItemsOperation implements QueryOperation<GetItem, ItemRecord> {

        @Override
        public ItemRecord execute(GetItem input) {
            return new ItemRecord(input.id(), "n", new AuthorRecord("a", "US"));
        }
    }

    /** The input of {@code Fail}. */
    public record Fail(String code) {
    }

    /** The output of {@code Fail}. */
    public record Failure(String message) {
    }

    /** Bound to {@code Fail}; always throws. */
    public static class FailOperation implements QueryOperation<Fail, Failure> {

        @Override
        public Failure execute(Fail input) {
            throw new IllegalStateException("fixture failure");
        }
    }

    /**
     * A record whose type grows with each level, so it is deeper than the type walk follows.
     *
     * @param value the value
     * @param next the next level
     * @param <T> grows with each level
     */
    public record Grow<T>(T value, Grow<List<T>> next) {
    }

    /** Takes a type deeper than the type walk follows. */
    public static class GrowOperation implements QueryOperation<Grow<String>, Void> {

        @Override
        public Void execute(Grow<String> input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Returns a type that branches three ways on each of ten levels: more types than the type walk visits. */
    public static class WideOperation implements QueryOperation<GetItem, Wide1<String>> {

        @Override
        public Wide1<String> execute(GetItem input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Level 1 of a type that branches three ways on each level; its type argument grows with each level. */
    public record Wide1<T>(Wide2<List<T>> a, Wide2<Set<T>> b, Wide2<Optional<T>> c) {
    }

    /** Level 2 of {@link Wide1}. */
    public record Wide2<T>(Wide3<List<T>> a, Wide3<Set<T>> b, Wide3<Optional<T>> c) {
    }

    /** Level 3 of {@link Wide1}. */
    public record Wide3<T>(Wide4<List<T>> a, Wide4<Set<T>> b, Wide4<Optional<T>> c) {
    }

    /** Level 4 of {@link Wide1}. */
    public record Wide4<T>(Wide5<List<T>> a, Wide5<Set<T>> b, Wide5<Optional<T>> c) {
    }

    /** Level 5 of {@link Wide1}. */
    public record Wide5<T>(Wide6<List<T>> a, Wide6<Set<T>> b, Wide6<Optional<T>> c) {
    }

    /** Level 6 of {@link Wide1}. */
    public record Wide6<T>(Wide7<List<T>> a, Wide7<Set<T>> b, Wide7<Optional<T>> c) {
    }

    /** Level 7 of {@link Wide1}. */
    public record Wide7<T>(Wide8<List<T>> a, Wide8<Set<T>> b, Wide8<Optional<T>> c) {
    }

    /** Level 8 of {@link Wide1}. */
    public record Wide8<T>(Wide9<List<T>> a, Wide9<Set<T>> b, Wide9<Optional<T>> c) {
    }

    /** Level 9 of {@link Wide1}. */
    public record Wide9<T>(Wide10<List<T>> a, Wide10<Set<T>> b, Wide10<Optional<T>> c) {
    }

    /** Level 10 of {@link Wide1}, the last; its type argument only keeps the types of each branch distinct. */
    @SuppressWarnings("UnusedTypeParameter")
    public record Wide10<T>(String end) {
    }
}
