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
import java.util.UUID;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.conditional.VersionCondition;
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

    /** Returns a page inside an {@code Optional}, which is not the operation's direct output. */
    public static class OptionalPageOperation implements QueryOperation<GetItem, Optional<PageResult<ItemRecord>>> {

        @Override
        public Optional<PageResult<ItemRecord>> execute(GetItem input) {
            return Optional.empty();
        }
    }

    /** The sort keys of {@link PagedItems}. */
    public enum ItemSort {
        NAME, CREATED_AT
    }

    /** An enum without constants, which can't be a sort allow-list. */
    public enum NoSort {
    }

    /** A constant whose lower-case name has a character outside {@code a-z0-9_}. */
    public enum DollarSort {
        TITLE$
    }

    /** Two constants with the same lower-case name. */
    @SuppressWarnings("EnumConstantNaming")
    public enum ClashingSort {
        NAME, Name
    }

    /** A filter and a {@code PageQuery}. */
    public record PagedItems(String shelf, PageQuery page) {
    }

    /** Two {@code PageQuery} components. */
    public record TwoPages(PageQuery first, PageQuery second) {
    }

    /** A {@code PageQuery} inside a nested record. */
    public record NestedPage(Inner inner) {

        /** Holds the page. */
        public record Inner(PageQuery page) {
        }
    }

    /** An input that holds a {@code PageResult}. */
    public record PageInInput(PageResult<String> page) {
    }

    /** An output that holds a {@code PageQuery}. */
    public record PageQueryOutput(PageQuery query) {
    }

    /** Lists a shelf's items by page. */
    public static class PagedItemsOperation implements QueryOperation<PagedItems, PageResult<ItemRecord>> {

        @Override
        public PageResult<ItemRecord> execute(PagedItems input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** A book of {@code paging.proto}'s {@code PagedBook}. */
    public record BookRecord(String id, String title, int publishedYear) {
    }

    /** The sort keys of {@code paging.proto}'s books. */
    public enum BookSort {
        TITLE, PUBLISHED_YEAR
    }

    /** Lists a shelf's books by page: {@code paging.proto}'s {@code ListBooks}. */
    public static class PagedBooksOperation implements QueryOperation<PagedItems, PageResult<BookRecord>> {

        @Override
        public PageResult<BookRecord> execute(PagedItems input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Lists every book: a {@code PageQuery} input, {@code paging.proto}'s {@code ListAll}. */
    public static class ListAllBooksOperation implements QueryOperation<PageQuery, PageResult<BookRecord>> {

        @Override
        public PageResult<BookRecord> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** A value the cursor digest can't write: a class with no properties. */
    public static final class Opaque {
    }

    /** A paged input with a component the digest can't write (set with {@code .context}). */
    public record PagedOpaque(Opaque opaque, PageQuery page) {
    }

    /** A paged input whose every component the digest writes, gRPC's {@code bytes} and {@code map} keys included. */
    @SuppressWarnings("ArrayRecordComponent")
    public record PagedRich(byte[] data, Map<Integer, List<AuthorRecord>> authors, Set<UUID> ids,
            @Nullable VersionCondition<Long> version, PageQuery page) {
    }

    /** Takes {@link PagedOpaque}. */
    public static class PagedOpaqueOperation implements QueryOperation<PagedOpaque, PageResult<BookRecord>> {

        @Override
        public PageResult<BookRecord> execute(PagedOpaque input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Takes {@link PagedRich}. */
    public static class PagedRichOperation implements QueryOperation<PagedRich, PageResult<BookRecord>> {

        @Override
        public PageResult<BookRecord> execute(PagedRich input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** A component named like the {@code page_size} page field, beside a {@code PageQuery}. */
    public record PagedWithSize(int pageSize, PageQuery page) {
    }

    /** Takes a component that reads a page field. */
    public static class PagedWithSizeOperation implements QueryOperation<PagedWithSize, PageResult<BookRecord>> {

        @Override
        public PageResult<BookRecord> execute(PagedWithSize input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Takes two {@code PageQuery} components. */
    public static class TwoPagesOperation implements QueryOperation<TwoPages, PageResult<ItemRecord>> {

        @Override
        public PageResult<ItemRecord> execute(TwoPages input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Takes a {@code PageQuery} in a nested record. */
    public static class NestedPageOperation implements QueryOperation<NestedPage, PageResult<ItemRecord>> {

        @Override
        public PageResult<ItemRecord> execute(NestedPage input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Takes a {@code PageResult}. */
    public static class PageInInputOperation implements QueryOperation<PageInInput, ItemRecord> {

        @Override
        public ItemRecord execute(PageInInput input) {
            return new ItemRecord("i", "n", new AuthorRecord("a", "US"));
        }
    }

    /** Returns a {@code PageQuery}. */
    public static class PageQueryOutputOperation implements QueryOperation<GetItem, PageQueryOutput> {

        @Override
        public PageQueryOutput execute(GetItem input) {
            return new PageQueryOutput(PageQuery.first(1));
        }
    }

    /** Returns a page of pages. */
    public static class PageOfPagesOperation implements QueryOperation<GetItem, PageResult<PageResult<ItemRecord>>> {

        @Override
        public PageResult<PageResult<ItemRecord>> execute(GetItem input) {
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

    /** The key of a doc ({@code conditional.proto}). */
    public record DocKey(String id) {
    }

    /** A doc, versioned. */
    public record DocView(String id, String text, long version) {
    }

    /** A doc with components of types that are not version types. */
    public record TaggedDoc(String id, String text, long version, List<String> tags, boolean archived) {
    }

    /** The input of a create: no version. */
    public record NewDoc(String text) {
    }

    /** An update, optionally conditional. */
    public record DocUpdate(String id, String text, @Nullable VersionCondition<Long> version) {
    }

    /** A delete, always conditional. */
    public record DocDeletion(String id, VersionCondition<Long> version) {
    }

    /** An input with two conditions. */
    public record TwoConditions(String id, VersionCondition<Long> version, @Nullable VersionCondition<Long> other) {
    }

    /** A condition in a nested record. */
    public record NestedCondition(String id, DocDeletion deletion) {
    }

    /** A condition inside an {@code Optional}. */
    public record WrappedCondition(String id, Optional<VersionCondition<Long>> version) {
    }

    /** A condition on {@code Object}, which every etag value is, and which never equals a version of another type. */
    public record ObjectCondition(String id, VersionCondition<Object> version) {
    }

    /** A condition without a version type. */
    public record WildcardCondition(String id, VersionCondition<?> version) {
    }

    /** A condition on a UUID: a string converts to it, but it is not a version type. */
    public record UuidCondition(String id, VersionCondition<UUID> version) {
    }

    /** A read whose input has an optional condition, as REST binds one from If-None-Match on GET. */
    public static class ReadUpdateOperation implements QueryOperation<DocUpdate, DocView> {

        @Override
        public DocView execute(DocUpdate input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes a condition on a UUID. */
    public static class UuidConditionOperation implements CommandOperation<UuidCondition, Void> {

        @Override
        public Void execute(UuidCondition input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Reads a doc. */
    public static class GetDocOperation implements QueryOperation<DocKey, Optional<DocView>> {

        @Override
        public Optional<DocView> execute(DocKey input) {
            return Optional.empty();
        }
    }

    /** Reads a doc with tags. */
    public static class GetTaggedDocOperation implements QueryOperation<DocKey, TaggedDoc> {

        @Override
        public TaggedDoc execute(DocKey input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Lists docs. */
    public static class ListDocsOperation implements QueryOperation<DocKey, List<DocView>> {

        @Override
        public List<DocView> execute(DocKey input) {
            return List.of();
        }
    }

    /** Creates a doc. */
    public static class CreateDocOperation implements CommandOperation<NewDoc, DocView> {

        @Override
        public DocView execute(NewDoc input) {
            return new DocView("d", input.text(), 1);
        }
    }

    /** Updates a doc. */
    public static class UpdateDocOperation implements CommandOperation<DocUpdate, DocView> {

        @Override
        public DocView execute(DocUpdate input) {
            return new DocView(input.id(), input.text(), 2);
        }
    }

    /** Deletes a doc. */
    public static class DeleteDocOperation implements CommandOperation<DocDeletion, Void> {

        @Override
        public Void execute(DocDeletion input) {
            throw new UnsupportedOperationException();
        }
    }

    /** A read whose input has a condition. */
    public static class ReadDeletionOperation implements QueryOperation<DocDeletion, DocView> {

        @Override
        public DocView execute(DocDeletion input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Returns a condition. */
    public static class ConditionOutputOperation implements QueryOperation<DocKey, DocDeletion> {

        @Override
        public DocDeletion execute(DocKey input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes two conditions. */
    public static class TwoConditionsOperation implements CommandOperation<TwoConditions, Void> {

        @Override
        public Void execute(TwoConditions input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes a condition in a nested record. */
    public static class NestedConditionOperation implements CommandOperation<NestedCondition, Void> {

        @Override
        public Void execute(NestedCondition input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes a condition inside an {@code Optional}. */
    public static class WrappedConditionOperation implements CommandOperation<WrappedCondition, Void> {

        @Override
        public Void execute(WrappedCondition input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes a condition on {@code Object}. */
    public static class ObjectConditionOperation implements CommandOperation<ObjectCondition, Void> {

        @Override
        public Void execute(ObjectCondition input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Takes a condition without a version type. */
    public static class WildcardConditionOperation implements CommandOperation<WildcardCondition, Void> {

        @Override
        public Void execute(WildcardCondition input) {
            throw new UnsupportedOperationException();
        }
    }
}
