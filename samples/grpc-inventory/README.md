# Sample: grpc-inventory, the gRPC tour

A stock inventory served over gRPC, in memory. It is laid out as a real Synapse service: the `.proto`, the classes
generated from it, the records and the error codes are in a contract module, which a caller can depend on, and the
operations, the store and the gRPC bindings are in the service module. There is no REST API and no web server.

It uses `synapse-api-grpc`, `synapse-logging-log4j2` and the Synapse test modules. For REST see
[`hello-rest`](../hello-rest/README.md) and [`rest-books`](../rest-books/README.md); for one operation served over
both protocols, see the reference application ([`reference-bookstore`](../reference-bookstore/README.md)).

## What it shows

Code paths are under `contract-inventory/src/main/` and `service-inventory/src/main/java/io/americanexpress/synapse/samples/inventory/`.

| Feature | Where |
|---|---|
| The `.proto` in a contract module, generated into `contract.proto` by the `protobuf-maven-plugin` that `synapse-parent` configures; the records and error codes next to it, with no protocol in them | `contract-inventory/` |
| One `GrpcBindings` bean, which is all the gRPC configuration: five methods, each bound to an operation with `.unary(...)` | `api/grpc/InventoryGrpcConfiguration.java` |
| Every field kind mapped by name: `string` to `UUID`, `Timestamp` to `Instant`, `Duration`, `Int32Value` and `StringValue` to nullable components, an enum by name, `repeated`, `map` and a nested message | `contract/Item.java`, `inventory.proto` |
| A renamed field (`sku_code` is the component `sku`) and a `oneof` (`price`, two `@Nullable` components), both with `.map(...)`; `Item.price()` returns the member that is set as a sealed `Price` | `InventoryGrpcConfiguration`, `contract/Price.java` |
| The version as an entity tag in `etag` (`.etag(Item::version)`), on `GetItem` and `CreateItem` only | [Try it](#try-it) |
| Paging with AIP-158 fields: `page_size`, `page_token`, `order_by` (`.sortable(SortKey.class)`), and `next_page_token`, `has_next`, `total_size` in the response | [Pages](#pages) |
| A request-context attribute from metadata: `x-channel` fills `CreateItemRequest.channel` (`.context(...)`) | [Try it](#try-it) |
| Error details: `ErrorInfo` (reason, domain, correlation id) on every error, `BadRequest` with the proto field names | [Stock](#stock), [Errors](#errors) |
| A per-operation status override: `OUT_OF_STOCK` is `ABORTED` (a `CONFLICT`), and `FAILED_PRECONDITION` for `adjust-stock` | `application.yaml` |
| The correlation id: read from `x-correlation-id` or generated, echoed in the response headers or trailers | [Try it](#try-it) |
| Server reflection for grpcurl, on in `local` only; gRPC health (`grpc.health.v1.Health`) | `application-local.yaml`, [Health](#health) |
| The environment: `spring.profiles.active: ${ENV}` in `application.yaml`, and one file per environment | `service-inventory/src/main/resources/` |
| Tests at every level, and the layering rules | [Tests](#tests) |

The packages follow Synapse's layering: `api.grpc` calls `operation`, which calls `data`; every layer may use
`contract`, and nothing calls `api`. `ArchitectureTest` checks it with
`SynapseArchitecture.forApplication(InventoryApplication.class)`, whose root package includes the contract module's
`contract` package: it must stay protocol-free, and the generated classes in `contract.proto` are exempt.

## Run it

You need JDK 25 to build (see [Before you start](../README.md#before-you-start)). Then, from the repository root,
in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/grpc-inventory/service-inventory -am package -DskipTests
ENV=local java -jar samples/grpc-inventory/service-inventory/target/service-inventory-2.0.0-SNAPSHOT.jar
```

The first command builds the service, its contract module and the Synapse modules they use, without running the tests
(`./mvnw verify` runs them). The second runs the `local` environment: `application.yaml` maps
`spring.profiles.active: ${ENV}`, and `application-local.yaml` turns on server reflection and status descriptions
(`synapse.errors.include-detail: true`). Without `ENV` the service doesn't start: it needs exactly one environment.

`spring-boot:run` would run the `local` environment too (the service's pom sets `spring-boot.run.profiles`), but it
runs the module alone, so it needs the contract module in your local Maven repository, and this sample's modules
never install (`maven.install.skip`). Use `java -jar` here; in a service of your own, which installs its contract
module, `spring-boot:run` works as in `hello-rest`.

The service starts Boot's gRPC server on port 9090 and logs ECS JSON, one object per line. These are the lines that
matter (the `@timestamp`, `process` and `service` fields are left out here):

```
"message":"The following 1 profile is active: \"local\""
"message":"synapse.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor is rejected after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32)."
"message":"Registered gRPC service: synapse.samples.inventory.v1.InventoryService"
"message":"Registered gRPC service: grpc.reflection.v1.ServerReflection"
"message":"Registered gRPC service: grpc.health.v1.Health"
"message":"gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090], port: 9090"
"message":"Synapse gRPC exposes synapse.samples.inventory.v1.InventoryService/GetItem -> get-item on port 9090"
"message":"Synapse gRPC exposes synapse.samples.inventory.v1.InventoryService/CreateItem -> create-item on port 9090"
"message":"Synapse gRPC exposes synapse.samples.inventory.v1.InventoryService/ListItems -> list-items on port 9090"
"message":"Synapse gRPC exposes synapse.samples.inventory.v1.InventoryService/AdjustStock -> adjust-stock on port 9090"
"message":"Synapse gRPC exposes synapse.samples.inventory.v1.InventoryService/DeleteItem -> delete-item on port 9090"
"message":"Started InventoryApplication in 2.195 seconds (process running for 2.794)"
"message":"Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)\nConfiguration, highest priority first:\n  1. Synapse baggage fields\n  2. Java system properties\n  3. environment variables\n  4. application-local.yaml  service-inventory-2.0.0-SNAPSHOT.jar\n  5. application.yaml        service-inventory-2.0.0-SNAPSHOT.jar\n  6. logCorrelation\n  7. applicationInfo\n  8. defaults"
```

The WARN about `synapse.paging.cursor.key` is explained in [Safe defaults](#safe-defaults). The last line lists the
configuration sources of the running service. Stop the service with Ctrl+C.

The store starts with three items: `WID-001` (`00000000-0000-0000-0000-000000000001`, a fixed price), `GAD-002`
(`...0002`, discounted) and `GIZ-003` (`...0003`, discontinued, without a price).

## Call it with grpcurl

The calls below use [grpcurl](https://github.com/fullstorydev/grpcurl) 1.9.3 from its Docker image, pinned by digest
(read with `docker buildx imagetools inspect fullstorydev/grpcurl:v1.9.3`), so you need Docker and nothing else.
Define it once per shell:

```bash
grpcurl() {
  docker run --rm fullstorydev/grpcurl:v1.9.3@sha256:085e183ca334eb4e81ca81ee12cbb2b2737505d1d77f5e33dabc5d066593d998 "$@"
}
```

The container reaches the service on the host as `host.docker.internal:9090`. Docker Desktop (Windows, macOS) knows
that name; on Linux, add `--add-host=host.docker.internal:host-gateway` after `docker run --rm`. In Git Bash on
Windows, if `docker` isn't found, put Docker Desktop's `resources/bin` directory first on `PATH` (for a machine-wide
install, `export PATH="/c/Program Files/Docker/Docker/resources/bin:$PATH"`), and `export MSYS_NO_PATHCONV=1` so Git
Bash leaves the arguments alone.

Under `local`, server reflection is on, so grpcurl needs no `.proto` file. Every other environment keeps Synapse's
default, reflection off, so a caller there uses the contract module's stubs or its `.proto`. `inventory.proto` imports
`google/rpc/error_details.proto` although no message uses it, so that reflection also serves the error detail types
and grpcurl prints `ErrorInfo` and `BadRequest` as JSON; the build warns that the import is unused.

grpcurl prints protobuf JSON with the proto field names. It leaves out every field at its default (an empty string,
`0`, `false`, an empty list) and every unset message or wrapper, and writes an `int64` as a string and a `Duration` in
seconds.

```bash
grpcurl -plaintext host.docker.internal:9090 list
```

```
grpc.health.v1.Health
grpc.reflection.v1.ServerReflection
synapse.samples.inventory.v1.InventoryService
```

```bash
grpcurl -plaintext host.docker.internal:9090 describe synapse.samples.inventory.v1.InventoryService
```

```
synapse.samples.inventory.v1.InventoryService is a service:
service InventoryService {
  rpc AdjustStock ( .synapse.samples.inventory.v1.AdjustStockRequest ) returns ( .synapse.samples.inventory.v1.Item );
  rpc CreateItem ( .synapse.samples.inventory.v1.CreateItemRequest ) returns ( .synapse.samples.inventory.v1.Item );
  rpc DeleteItem ( .synapse.samples.inventory.v1.DeleteItemRequest ) returns ( .google.protobuf.Empty );
  rpc GetItem ( .synapse.samples.inventory.v1.GetItemRequest ) returns ( .synapse.samples.inventory.v1.Item );
  rpc ListItems ( .synapse.samples.inventory.v1.ListItemsRequest ) returns ( .synapse.samples.inventory.v1.ListItemsResponse );
}
```

```bash
grpcurl -plaintext host.docker.internal:9090 describe synapse.samples.inventory.v1.Item
```

```
synapse.samples.inventory.v1.Item is a message:
message Item {
  string id = 1;
  string sku_code = 2;
  .synapse.samples.inventory.v1.Item.Status status = 3;
  repeated string tags = 4;
  map<string, string> attributes = 5;
  .synapse.samples.inventory.v1.Location location = 6;
  int32 quantity = 7;
  .google.protobuf.Timestamp restocked_at = 8;
  .google.protobuf.Duration lead_time = 9;
  .google.protobuf.Int32Value reorder_level = 10;
  oneof price {
    .synapse.samples.inventory.v1.Money fixed = 11;
    .synapse.samples.inventory.v1.Discount discounted = 12;
  }
  .google.protobuf.StringValue created_via = 13;
  int64 version = 14;
  string etag = 15;
  enum Status {
    STATUS_UNSPECIFIED = 0;
    STATUS_ACTIVE = 1;
    STATUS_DISCONTINUED = 2;
  }
}
```

### Try it

Read an item, with your own correlation id; `-v` shows the metadata:

```bash
grpcurl -plaintext -v -H 'x-correlation-id: demo-123' -d '{"id": "00000000-0000-0000-0000-000000000001"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/GetItem
```

```

Resolved method descriptor:
rpc GetItem ( .synapse.samples.inventory.v1.GetItemRequest ) returns ( .synapse.samples.inventory.v1.Item );

Request metadata to send:
x-correlation-id: demo-123

Response headers received:
content-type: application/grpc
grpc-accept-encoding: gzip
x-correlation-id: demo-123

Response contents:
{
  "id": "00000000-0000-0000-0000-000000000001",
  "sku_code": "WID-001",
  "status": "STATUS_ACTIVE",
  "tags": [
    "hardware"
  ],
  "attributes": {
    "color": "blue"
  },
  "location": {
    "warehouse": "east",
    "aisle": "A1",
    "bin": 3
  },
  "quantity": 40,
  "restocked_at": "2026-11-01T09:00:00Z",
  "lead_time": "259200s",
  "reorder_level": 10,
  "fixed": {
    "currency_code": "USD",
    "amount": "19.99"
  },
  "version": "1",
  "etag": "\"1\""
}

Response trailers received:
(empty)
Sent 1 request and received 1 response
```

Each field comes from the `Item` record's component of the same name, converted by its type: `restocked_at` from an
`Instant`, `lead_time` from a `Duration` (three days), `reorder_level` from an `Integer` (a wrapper, so `null` would
leave it unset), `status` from `ItemStatus.ACTIVE` (the proto value is the constant after the enum's prefix). Three
fields need a `.map(...)` in the binding: `sku_code` (the component is `sku`) and the `oneof price` members. `etag` is
the version as an entity tag, `"1"` with its double quotes, which REST would send as `ETag`; `GetItem` and
`CreateItem` carry it. `created_via` is unset because the seed item has no channel. The correlation id comes back in
the response headers (in the trailers when the call fails without headers).

The discounted item sets the other member of the `oneof`:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000002"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/GetItem
```

```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "sku_code": "GAD-002",
  "status": "STATUS_ACTIVE",
  "tags": [
    "electronics",
    "gift"
  ],
  "location": {
    "warehouse": "east",
    "aisle": "B4",
    "bin": 12
  },
  "quantity": 5,
  "discounted": {
    "list_price": {
      "currency_code": "USD",
      "amount": "49.00"
    },
    "percent_off": 20
  },
  "version": "1",
  "etag": "\"1\""
}
```

In the record, `Item.fixedPrice` and `Item.discountedPrice` are two `@Nullable` components, each mapped to its member
with `.map("fixed", Item::fixedPrice)` and `.map("discounted", Item::discountedPrice)`. The one that has a value sets
its member. The `oneof` is part of the contract, so the contract's records say it too: `Item`'s and
`CreateItemRequest`'s constructors reject both prices. A REST binding of the same operations would then accept and
return the same values as gRPC (a body with both is `VALIDATION_FAILED`); Synapse's `INTERNAL` for a gRPC response
with both is only a backstop.

Add an item, with the channel in the `x-channel` metadata:

```bash
grpcurl -plaintext -H 'x-channel: grpcurl' \
  -d '{"sku_code": "SPR-004", "tags": ["spare"], "location": {"warehouse": "west", "aisle": "D1", "bin": 2}, "quantity": 12, "lead_time": "36h", "discounted": {"list_price": {"currency_code": "USD", "amount": "8.00"}, "percent_off": 25}}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/CreateItem
```

```json
{
  "id": "c6d77a64-9bc8-48c5-bd89-894f9b2e7619",
  "sku_code": "SPR-004",
  "status": "STATUS_ACTIVE",
  "tags": [
    "spare"
  ],
  "location": {
    "warehouse": "west",
    "aisle": "D1",
    "bin": 2
  },
  "quantity": 12,
  "restocked_at": "2026-11-07T19:55:52.547089500Z",
  "lead_time": "129600s",
  "discounted": {
    "list_price": {
      "currency_code": "USD",
      "amount": "8.00"
    },
    "percent_off": 25
  },
  "created_via": "grpcurl",
  "version": "1",
  "etag": "\"1\""
}
```

`synapse.api.grpc.context.metadata.channel: x-channel` puts the metadata in the request context, and the binding's
`.context(CreateItemRequest::channel, ...)` fills the component from it; the request message has no field for it, so a
caller can't set it in the body. The stock it starts with counts as a restock, at the service's clock.

### Pages

`ListItems` pages with the AIP-158 fields. Two items per page, by quantity, largest first:

```bash
grpcurl -plaintext -d '{"page_size": 2, "order_by": "quantity desc"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/ListItems
```

```json
{
  "items": [
    {
      "id": "00000000-0000-0000-0000-000000000001",
      "sku_code": "WID-001",
      "status": "STATUS_ACTIVE",
      "tags": [
        "hardware"
      ],
      "attributes": {
        "color": "blue"
      },
      "location": {
        "warehouse": "east",
        "aisle": "A1",
        "bin": 3
      },
      "quantity": 40,
      "restocked_at": "2026-11-01T09:00:00Z",
      "lead_time": "259200s",
      "reorder_level": 10,
      "fixed": {
        "currency_code": "USD",
        "amount": "19.99"
      },
      "version": "1"
    },
    {
      "id": "c6d77a64-9bc8-48c5-bd89-894f9b2e7619",
      "sku_code": "SPR-004",
      "status": "STATUS_ACTIVE",
      "tags": [
        "spare"
      ],
      "location": {
        "warehouse": "west",
        "aisle": "D1",
        "bin": 2
      },
      "quantity": 12,
      "restocked_at": "2026-11-07T19:55:52.547089500Z",
      "lead_time": "129600s",
      "discounted": {
        "list_price": {
          "currency_code": "USD",
          "amount": "8.00"
        },
        "percent_off": 25
      },
      "created_via": "grpcurl",
      "version": "1"
    }
  ],
  "next_page_token": "At1dJUvCKLcXX7usmJlBVozPemyOjEsRFicSawHhW4hJps4b6hkPCW_5ykY",
  "has_next": true,
  "total_size": 4
}
```

`order_by` takes the constants of `ListItemsRequest.SortKey` in lower
case (`sku_code`, `quantity`), each optionally followed by `desc`; unset, the order is by SKU. Items in a list carry
no `etag`. Pass the `next_page_token` you got, with the same `page_size`, `order_by` and filter:

```bash
grpcurl -plaintext -d '{"page_size": 2, "order_by": "quantity desc", "page_token": "At1dJUvCKLcXX7usmJlBVozPemyOjEsRFicSawHhW4hJps4b6hkPCW_5ykY"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/ListItems
```

```json
{
  "items": [
    {
      "id": "00000000-0000-0000-0000-000000000002",
      "sku_code": "GAD-002",
      "status": "STATUS_ACTIVE",
      "tags": [
        "electronics",
        "gift"
      ],
      "location": {
        "warehouse": "east",
        "aisle": "B4",
        "bin": 12
      },
      "quantity": 5,
      "discounted": {
        "list_price": {
          "currency_code": "USD",
          "amount": "49.00"
        },
        "percent_off": 20
      },
      "version": "1"
    },
    {
      "id": "00000000-0000-0000-0000-000000000003",
      "sku_code": "GIZ-003",
      "status": "STATUS_DISCONTINUED",
      "location": {
        "warehouse": "west",
        "aisle": "C2",
        "bin": 7
      },
      "version": "1"
    }
  ],
  "total_size": 4
}
```

The last page has no `next_page_token`, and `has_next` is `false` (left out, as a default). `ListItemsOperation`
pages by offset and returns no cursor; the edge gives the caller a token that carries the next offset, encrypted and
bound to the operation, the filter and the sort. A token sent with another sort is `INVALID_ARGUMENT`, before the
operation runs (the error details are explained under [Stock](#stock)):

```bash
grpcurl -plaintext -d '{"page_size": 2, "order_by": "sku_code", "page_token": "At1dJUvCKLcXX7usmJlBVozPemyOjEsRFicSawHhW4hJps4b6hkPCW_5ykY"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/ListItems
```

```
ERROR:
  Code: InvalidArgument
  Message: The request is invalid
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "fdcdaf32-8fd5-4c73-b597-cf76d5256031"
    	  },
    	  "reason": "VALIDATION_FAILED"
    	}
  2)	{
    	  "@type": "type.googleapis.com/google.rpc.BadRequest",
    	  "field_violations": [
    	    {
    	      "field": "page_token",
    	      "description": "is not valid for this request"
    	    }
    	  ]
    	}
```

### Stock

Add stock to the gadget:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000002", "delta": 10}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/AdjustStock
```

```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "sku_code": "GAD-002",
  "status": "STATUS_ACTIVE",
  "tags": [
    "electronics",
    "gift"
  ],
  "location": {
    "warehouse": "east",
    "aisle": "B4",
    "bin": 12
  },
  "quantity": 15,
  "restocked_at": "2026-11-07T19:56:05.671893500Z",
  "discounted": {
    "list_price": {
      "currency_code": "USD",
      "amount": "49.00"
    },
    "percent_off": 20
  },
  "version": "2"
}
```

The version went to 2, and there is no `etag`: Synapse puts an entity tag on reads and creates only, so a caller reads
the item again for the new one. `AdjustStockRequest` and `DeleteItemRequest` have no `VersionCondition`, so neither
method takes an `etag`: an adjustment is relative and atomic, and a delete removes whatever version is there. For
conditional writes with `etag`, see the reference application.

Taking more than is in stock is `OUT_OF_STOCK`, a `CONFLICT`, which is `ABORTED`; `application.yaml` makes it
`FAILED_PRECONDITION` for `adjust-stock` (`synapse.api.grpc.operations.adjust-stock.errors.status`). A failed call is
a status code, a description, and error details; with `include-detail` on (`local`), the description is the formatted
message:

```bash
grpcurl -plaintext -H 'x-correlation-id: demo-456' -d '{"id": "00000000-0000-0000-0000-000000000002", "delta": -100}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/AdjustStock
```

```
ERROR:
  Code: FailedPrecondition
  Message: Item 00000000-0000-0000-0000-000000000002 has only 15 in stock
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "demo-456"
    	  },
    	  "reason": "OUT_OF_STOCK"
    	}
```

`ErrorInfo.reason` is the error code, `domain` is `spring.application.name`, and `metadata.correlationId` is the
call's correlation id. The failed operation logged one line, with the same correlation id:

```
"log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"} ... "message":"Operation adjust-stock failed with OUT_OF_STOCK (CONFLICT): Item 00000000-0000-0000-0000-000000000002 has only 15 in stock","correlationId":"demo-456","errorCategory":"CONFLICT","errorCode":"OUT_OF_STOCK","operation":"adjust-stock"
```

### Errors

The other errors have the same shape.

An unknown item is `NOT_FOUND`, with the reason the binding's `.notFound(InventoryErrors.ITEM_NOT_FOUND)` declares.
Its message template needs the id, which the edge doesn't have, so the description is `NOT_FOUND`'s own:

```bash
grpcurl -plaintext -H 'x-correlation-id: demo-123' -d '{"id": "00000000-0000-0000-0000-000000000099"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/GetItem
```

```
ERROR:
  Code: NotFound
  Message: The resource was not found
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "demo-123"
    	  },
    	  "reason": "ITEM_NOT_FOUND"
    	}
```

An empty result is not an operation failure, so nothing is logged.

A request that breaks `CreateItemRequest`'s constraints (`@NotBlank` on the SKU) is `INVALID_ARGUMENT`, with a
`BadRequest` whose violation names the proto field, `sku_code`, not the component:

```bash
grpcurl -plaintext -d '{"sku_code": " ", "location": {"warehouse": "west", "aisle": "D1", "bin": 2}}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/CreateItem
```

```
ERROR:
  Code: InvalidArgument
  Message: The request is invalid
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "e579bde8-c1c8-4ad9-a8f3-db67e9046868"
    	  },
    	  "reason": "VALIDATION_FAILED"
    	}
  2)	{
    	  "@type": "type.googleapis.com/google.rpc.BadRequest",
    	  "field_violations": [
    	    {
    	      "field": "sku_code",
    	      "description": "must not be blank"
    	    }
    	  ]
    	}
```

Stack traces, exception class names and internal messages never reach the caller. (The ids and times change on every
run, and so do the tokens.)

### Delete

Delete the gizmo; the response is `google.protobuf.Empty`:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000003"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/DeleteItem
```

```json
{}
```

Deleting it again fails in the operation, which throws `ITEM_NOT_FOUND` with the id, so the description is the code's
message:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000003"}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/DeleteItem
```

```
ERROR:
  Code: NotFound
  Message: Item 00000000-0000-0000-0000-000000000003 was not found
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "13c721ef-d980-4a0d-82ca-0512bb934e99"
    	  },
    	  "reason": "ITEM_NOT_FOUND"
    	}
```

### Health

Boot's gRPC health service answers for the whole server:

```bash
grpcurl -plaintext host.docker.internal:9090 grpc.health.v1.Health/Check
```

```json
{
  "status": "SERVING"
}
```

## Safe defaults

Synapse's defaults are the same in every environment; only `application-local.yaml` turns server reflection and status
descriptions on. Stop the service and start it with descriptions turned off again:

```bash
ENV=local java -jar samples/grpc-inventory/service-inventory/target/service-inventory-2.0.0-SNAPSHOT.jar \
  --synapse.errors.include-detail=false
```

Errors then have no description; the details stay:

```bash
grpcurl -plaintext -d '{"id": "00000000-0000-0000-0000-000000000099", "delta": 1}' \
  host.docker.internal:9090 synapse.samples.inventory.v1.InventoryService/AdjustStock
```

```
ERROR:
  Code: NotFound
  Message: 
  Details:
  1)	{
    	  "@type": "type.googleapis.com/google.rpc.ErrorInfo",
    	  "domain": "inventory",
    	  "metadata": {
    	    "correlationId": "c619c3d0-a085-4c8f-b535-f21c3adde7a0"
    	  },
    	  "reason": "ITEM_NOT_FOUND"
    	}
```

The log line of the failure still carries the message (`Item 00000000-0000-0000-0000-000000000099 was not found`), so
the `correlationId` a caller reports leads to it.

No cursor key is set, so Synapse generates one at startup and warns, at every start, that page tokens won't survive a
restart or work on another instance (the WARN in [Run it](#run-it)). To silence it, give the service a key: stop it,
and start it again with the key in the environment variable `SYNAPSE_PAGING_CURSOR_KEY`, as `application-local.yaml`
says. Boot binds that variable to `synapse.paging.cursor.key` (dots become underscores, letters upper case), so no
file names the key. The variable is set for the `java` command only, so the rest of your shell is unchanged. A real
deployment sets the same key on every instance, from the environment or a secret store, never from a file in the
repository:

```bash
KEY=$(openssl rand -base64 32)
SYNAPSE_PAGING_CURSOR_KEY=$KEY ENV=local java -jar samples/grpc-inventory/service-inventory/target/service-inventory-2.0.0-SNAPSHOT.jar
```

The startup log then has no line about `synapse.paging.cursor.key`, and no WARN at all. A page token now survives a
restart: stop the service, run the same `java` command again (with the same `$KEY`), and a `next_page_token` from
before the restart still pages.

## Configuration

`service-inventory/src/main/resources/application.yaml` lists the settings worth changing while you try the service,
with comments: the application name (the `ErrorInfo` domain), the correlation key, error detail, the paging key, the
`x-channel` mapping, the deadline, the concurrency limit, and the status override for `adjust-stock`. The override's
key is the code in brackets, quoted so YAML keeps the brackets:

```yaml
synapse:
  api:
    grpc:
      operations:
        adjust-stock:
          errors:
            status: { "[OUT_OF_STOCK]": FAILED_PRECONDITION }
```

`synapse.api.grpc.errors.status.[CODE]` overrides a code for every operation. `application-local.yaml` holds the local
conveniences. The sample builds without Spring AOT processing, which `synapse-parent` runs only with `-Paot`, so it has
no build environment file (`application-build.yaml`). Under AOT, reflection is a build-time switch: the
[reference bookstore](../reference-bookstore/README.md#aot-mode) shows the opt-in, and a run in `local` that fails
because of it.

The pom of each module is the pom of a real service, except the four `maven.*.skip` properties in `pom.xml`, which
keep the sample from being published. `contract-inventory/pom.xml` declares the protobuf plugin with no configuration:
`synapse-parent` inherits it from `spring-boot-starter-parent`, with protoc, the grpc-java plugin and the generate
execution.

## Tests

| Test | What it checks | Kind |
|---|---|---|
| `operation/InventoryOperationsTest` | The operations with the real pipeline and a `MutableClock` (`@SynapseOperationTest`): create and read, restock times, `OUT_OF_STOCK`, a delta past the int range as `VALIDATION_FAILED`, a zero delta that changes nothing, `ITEM_NOT_FOUND`, validation, at most one price in both records, filters, sorting and offset pages, the discounted price | unit |
| `api/grpc/InventoryGrpcTest` | The bindings on the in-process transport with every operation mocked (`@SynapseGrpcTest`): every field kind both ways, the `oneof` and the renamed field, `NOT_FOUND` with `ErrorInfo`, `BadRequest` field names, the per-operation override, `x-channel`, page tokens over five items, and the slice's message when a mock is missing | unit |
| `ArchitectureTest` | The layering rules, with the contract package protocol-free | unit |
| `InventoryApplicationIT` | The whole service on Netty on a free port: the correlation id echoed; a call whose deadline has passed when it reaches the service (a test interceptor expires it on the server, as one that expired in transit) is `DEADLINE_EXCEEDED` with `ErrorInfo` from Synapse, and creates nothing; reflection off by default and on with `local`'s setting | integration |

```bash
./mvnw -pl samples/grpc-inventory/service-inventory -am verify
```

runs them with everything the sample depends on (`*Test` in the `test` phase, `*IT` in `verify`). To run one unit
test class, or the integration test alone:

```bash
./mvnw -pl samples/grpc-inventory/service-inventory -am test -Dtest=InventoryGrpcTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl samples/grpc-inventory/service-inventory -am verify -Dit.test=InventoryApplicationIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

`-Djacoco.skip=true` turns off the coverage gate, which needs the tests of every module the build includes.

`service-inventory/src/test/resources/application-test.yaml` turns `synapse.errors.include-detail` on, so the tests can
check the descriptions.

## Layout

```
samples/grpc-inventory/
  pom.xml                                aggregator, parent synapse-parent; modules below
  contract-inventory/
    pom.xml                              protobuf-java, grpc-protobuf, grpc-stub, jakarta.validation-api, synapse-core
    src/main/proto/inventory/v1/
      inventory.proto                    InventoryService and its messages
    src/main/java/.../inventory/contract/
      Item, Location, Money, Discount    the records the operations return
      Price                              sealed: Money or Discount, the member of oneof price that is set
      ItemStatus                         the enum Item.Status maps to
      *Request                           the operations' inputs, with their constraints
      InventoryErrors                    ITEM_NOT_FOUND, OUT_OF_STOCK
    (target/generated-sources/protobuf)  contract.proto: the messages and InventoryServiceGrpc
  service-inventory/
    pom.xml                              the dependencies of a gRPC service, and the test modules
    src/main/java/.../inventory/
      InventoryApplication.java          @SpringBootApplication
      operation/                         GetItem, CreateItem, ListItems, AdjustStock, DeleteItem
      data/InMemoryItems                 the store, with three seed items
      api/grpc/InventoryGrpcConfiguration   GrpcBindings for InventoryService
    src/main/resources/
      application.yaml                   the name, ENV, x-channel, the adjust-stock override
      application-local.yaml             local conveniences: reflection and status descriptions
    src/test/java/.../inventory/         the tests above
    src/test/resources/
      application-test.yaml              the test environment: status descriptions on
```

## Make it yours

Copy the directory and change these:

* The three poms: the `groupId`, `artifactId`s and `description`s; the parent's `relativePath` (drop it when the parent
  comes from a repository); and remove the four `maven.*.skip` properties, which only keep this sample from being
  published. Install and deploy the contract module, so that callers can depend on it.
* The packages `io.americanexpress.synapse.samples.inventory...`, the proto `package` and `java_package`, and
  `InventoryApplication`.
* `spring.application.name` in `application.yaml`, which callers see as the `ErrorInfo` domain.
