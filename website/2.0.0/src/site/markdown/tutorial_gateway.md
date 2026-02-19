<!---
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<head>
  <title>Tutorial - Helix Gateway</title>
</head>

## [Helix Tutorial](./Tutorial.html): Helix Gateway

Helix Gateway is a new module introduced in Helix 2.0.0 that acts as a sidecar proxy between non-JVM application instances (participants) and the Helix cluster. It manages participant lifecycle and state transitions on behalf of remote applications via gRPC or file-based polling, eliminating the need for applications to embed the Helix Java client library directly.

### Overview

In a traditional Helix deployment, every participant must embed the Helix Java client (ZKHelixManager) to connect to ZooKeeper, register state model factories, and handle state transitions. This works well for JVM-based applications but creates a barrier for non-JVM systems (Python, Go, C++, etc.).

Helix Gateway solves this by running as a standalone service that:

* Manages ZooKeeper connections and Helix participant managers on behalf of remote applications
* Translates Helix state transition messages into gRPC calls (or file-based assignments) that any language can consume
* Tracks shard current states and target states in an in-memory cache
* Supports multiple clusters and multiple participants simultaneously

### Architecture

```
+---------------------+         gRPC / File         +---------------------+
|  Application        | <=========================> |  Helix Gateway      |
|  (any language)     |   ShardStateMessage /        |  Service            |
|                     |   ShardChangeRequests        |                     |
+---------------------+                             +----------+----------+
                                                               |
                                                               | ZooKeeper
                                                               |
                                                    +----------+----------+
                                                    |  Helix Controller   |
                                                    +---------------------+
```

The key components are:

* **GatewayServiceManager** - Top-level orchestrator that receives events from the service channel, maintains a registry of gateway participants (one per application instance), and dispatches state transition results.
* **HelixGatewayServiceChannel** - Interface for inbound/outbound communication. Two implementations are provided:
    * **HelixGatewayServiceGrpcService** (Push Mode) - A bidirectional streaming gRPC server. Applications connect and exchange `ShardStateMessage` / `ShardChangeRequests` over a persistent stream.
    * **HelixGatewayServicePollModeChannel** (Poll Mode) - A file-based channel where the gateway periodically polls participant liveness and current state from files, and writes target state assignments to a file.
* **HelixGatewayParticipant** - Wraps a `ZKHelixManager` for a single remote participant. It processes state transition messages from the Helix controller and forwards them to the application via the service channel.
* **HelixGatewayMultiTopStateStateModel** - A generic state model that delegates all transitions (using `@Transition(to = "*", from = "*")`) to the `HelixGatewayParticipant`, which in turn forwards them to the remote application.
* **GatewayCurrentStateCache** - In-memory cache that tracks both the reported current state and the target state for every instance/resource/shard in each cluster.

### gRPC Protocol

The gateway uses Protocol Buffers to define its service contract in `HelixGatewayService.proto`:

```protobuf
service HelixGatewayService {
  rpc report(stream ShardStateMessage) returns (stream ShardChangeRequests) {}
}
```

This is a **bidirectional streaming** RPC:

* **Client to Gateway** (`ShardStateMessage`): The application sends either:
    * `ShardState` - Initial connection message reporting the instance name, cluster name, and current state of all shards.
    * `ShardTransitionStatus` - Response after completing a state transition, reporting the resulting current state of each shard.

* **Gateway to Client** (`ShardChangeRequests`): The gateway sends a batch of shard change requests, each containing:
    * `stateChangeRequestType` - One of `ADD_SHARD`, `DELETE_SHARD`, or `CHANGE_ROLE`
    * `resourceName` - The resource identifier
    * `shardName` - The shard/partition identifier
    * `targetState` - The desired target state

### Connection Lifecycle

1. **Application connects** and sends a `ShardState` message with its instance name, cluster name, and current shard states.
2. **Gateway creates a `HelixGatewayParticipant`** which internally creates a `ZKHelixManager`, registers the generic state model factory, and connects to ZooKeeper.
3. **Helix controller assigns partitions** and sends state transition messages to the gateway participant.
4. **Gateway forwards transitions** to the application as `ShardChangeRequests` over the gRPC stream.
5. **Application performs the transition** and responds with a `ShardTransitionStatus` message.
6. **Gateway completes the transition** by signaling the `CompletableFuture` in the participant, which unblocks the state model handler.
7. **On disconnect**, the gateway removes the participant and disconnects the `HelixManager`.

### Building the Module

Requirements: JDK 11+, Maven 3.5.0+

The helix-gateway module uses the `protobuf-maven-plugin` to compile `.proto` files and generate gRPC stubs. Build from the project root:

```
git clone https://github.com/apache/helix.git
cd helix
git checkout tags/helix-2.0.0
mvn install package -DskipTests -pl helix-gateway -am
```

This produces two classifier JARs:

* `helix-gateway-2.0.0-jdk8.jar` - Compiled with JDK 8 target
* `helix-gateway-2.0.0-jdk11.jar` - Compiled with JDK 11 target

And an assembly tar package via `src/assemble/assembly.xml` containing `bin/`, `conf/`, and `repo/` directories.

### Maven Dependency

```xml
<dependency>
  <groupId>org.apache.helix</groupId>
  <artifactId>helix-gateway</artifactId>
  <version>2.0.0</version>
</dependency>
```

### Running the Gateway Service

The `HelixGatewayMain` class is the entry point. It accepts two arguments:

* `args[0]` - ZooKeeper address (e.g., `localhost:2199`)
* `args[1]` - gRPC server port (e.g., `12345`)

```
java -cp helix-gateway-2.0.0-jdk11.jar org.apache.helix.gateway.HelixGatewayMain localhost:2199 12345
```

### Programmatic Usage (Push Mode / gRPC)

To start the gateway service programmatically with the default gRPC push mode:

```java
import org.apache.helix.gateway.channel.GatewayServiceChannelConfig;
import org.apache.helix.gateway.service.GatewayServiceManager;

// Build configuration
GatewayServiceChannelConfig config =
    new GatewayServiceChannelConfig.GatewayServiceProcessorConfigBuilder()
        .setGrpcServerPort(12345)
        .setServerHeartBeatInterval(60)
        .setMaxAllowedClientHeartBeatInterval(60)
        .setClientTimeout(300)
        .setEnableReflectionService(true)
        .build();

// Create and start the gateway service manager
GatewayServiceManager manager = new GatewayServiceManager("localhost:2199", config);
manager.startService();

// ... gateway is now accepting gRPC connections ...

// Shutdown
manager.stopService();
```

### Programmatic Usage (Poll Mode / File-Based)

For environments where gRPC is not available (e.g., sidecar containers communicating via shared volumes), use the poll mode:

```java
import org.apache.helix.gateway.channel.GatewayServiceChannelConfig;
import org.apache.helix.gateway.channel.GatewayServiceChannelConfig.ChannelMode;
import org.apache.helix.gateway.channel.GatewayServiceChannelConfig.ChannelType;
import org.apache.helix.gateway.channel.GatewayServiceChannelConfig.FileBasedConfigType;
import org.apache.helix.gateway.service.GatewayServiceManager;

import java.util.HashMap;
import java.util.Map;

// Define participant liveness endpoints (cluster -> instance -> file path)
Map<String, Map<String, String>> livenessEndpoints = new HashMap<>();
Map<String, String> clusterInstances = new HashMap<>();
clusterInstances.put("instance_0", "/shared/liveness/instance_0.json");
clusterInstances.put("instance_1", "/shared/liveness/instance_1.json");
livenessEndpoints.put("my-cluster", clusterInstances);

// Build poll mode configuration
GatewayServiceChannelConfig config =
    new GatewayServiceChannelConfig.GatewayServiceProcessorConfigBuilder()
        .setChannelMode(ChannelMode.POLL_MODE)
        .setParticipantConnectionChannelType(ChannelType.FILE)
        .setShardStateProcessorType(ChannelType.FILE)
        .addPollModeConfig(FileBasedConfigType.PARTICIPANT_CURRENT_STATE_PATH,
            "/shared/current_state.json")
        .addPollModeConfig(FileBasedConfigType.SHARD_TARGET_STATE_PATH,
            "/shared/target_state.json")
        .setHealthCheckEndpointMap(livenessEndpoints)
        .setPollIntervalSec(10)
        .setPollStartDelaySec(5)
        .setPollHealthCheckTimeout(30)
        .setTargetFileUpdateIntervalSec(10)
        .build();

GatewayServiceManager manager = new GatewayServiceManager("localhost:2199", config);
manager.startService();
```

**File formats for poll mode:**

Liveness file (per instance):
```json
{"IsAlive": true, "LastUpdateTime": 1700000000}
```

Current state file (shared):
```json
{
  "my-cluster": {
    "instance_0": {
      "MyDB": {
        "MyDB_0": "ONLINE",
        "MyDB_1": "ONLINE"
      }
    }
  }
}
```

Target state file (written by gateway):
```json
{
  "Assignment": {
    "my-cluster": {
      "instance_0": {
        "MyDB": {
          "MyDB_0": "ONLINE",
          "MyDB_1": "OFFLINE"
        }
      }
    }
  },
  "Timestamp": 1700000060000
}
```

### Writing a gRPC Client (Application Side)

Any language with gRPC support can implement a client. Below is a Java example:

```java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import proto.org.apache.helix.gateway.HelixGatewayServiceGrpc;
import proto.org.apache.helix.gateway.HelixGatewayServiceOuterClass.*;

// Connect to the gateway
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("localhost", 12345)
    .usePlaintext()
    .build();

HelixGatewayServiceGrpc.HelixGatewayServiceStub stub =
    HelixGatewayServiceGrpc.newStub(channel);

// Open bidirectional stream
StreamObserver<ShardChangeRequests> responseObserver = new StreamObserver<ShardChangeRequests>() {
    @Override
    public void onNext(ShardChangeRequests requests) {
        // Handle each state change request from the gateway
        for (SingleShardChangeRequest req : requests.getRequestList()) {
            System.out.println("Transition: " + req.getResourceName()
                + "/" + req.getShardName()
                + " -> " + req.getTargetState()
                + " (type: " + req.getStateChangeRequestType() + ")");

            // Perform the actual state transition in your application...

            // Report the result back
            requestObserver.onNext(ShardStateMessage.newBuilder()
                .setShardTransitionStatus(ShardTransitionStatus.newBuilder()
                    .setInstanceName("instance_0")
                    .setClusterName("my-cluster")
                    .addShardTransitionStatus(SingleShardTransitionStatus.newBuilder()
                        .setResourceName(req.getResourceName())
                        .setShardName(req.getShardName())
                        .setCurrentState(req.getTargetState())
                        .build())
                    .build())
                .build());
        }
    }

    @Override
    public void onError(Throwable t) {
        System.err.println("Gateway error: " + t.getMessage());
    }

    @Override
    public void onCompleted() {
        System.out.println("Gateway closed connection");
    }
};

StreamObserver<ShardStateMessage> requestObserver = stub.report(responseObserver);

// Send initial connection message with current shard states
requestObserver.onNext(ShardStateMessage.newBuilder()
    .setShardState(ShardState.newBuilder()
        .setInstanceName("instance_0")
        .setClusterName("my-cluster")
        .addResourceState(SingleResourceState.newBuilder()
            .setResource("MyDB")
            .addShardStates(SingleShardState.newBuilder()
                .setShardName("MyDB_0")
                .setCurrentState("ONLINE")
                .build())
            .build())
        .build())
    .build());
```

### Configuration Reference

| Parameter | Default | Description |
|-----------|---------|-------------|
| `grpcServerPort` | (required) | Port for the gRPC server in push mode |
| `channelMode` | `PUSH_MODE` | `PUSH_MODE` (gRPC) or `POLL_MODE` (file-based) |
| `participantConnectionChannelType` | `GRPC_SERVER` | `GRPC_SERVER`, `GRPC_CLIENT`, or `FILE` |
| `shardStateChannelType` | `GRPC_SERVER` | `GRPC_SERVER`, `GRPC_CLIENT`, or `FILE` |
| `serverHeartBeatInterval` | 60 sec | gRPC keepalive ping interval |
| `maxAllowedClientHeartBeatInterval` | 60 sec | Minimum allowed client keepalive interval |
| `clientTimeout` | 300 sec | gRPC keepalive timeout before closing connection |
| `enableReflectionService` | true | Enable gRPC server reflection for debugging tools |
| `pollIntervalSec` | 60 sec | Polling interval for poll mode |
| `pollStartDelaySec` | 60 sec | Initial delay before first poll |
| `pollHealthCheckTimeoutSec` | 60 sec | Liveness file staleness timeout |
| `targetFileUpdateIntervalSec` | 60 sec | Interval for writing target state file |

### Supported State Models

Currently, Helix Gateway supports the **OnlineOffline** multi-top-state model type. The generic state model handler (`HelixGatewayMultiTopStateStateModel`) uses a wildcard transition `@Transition(to = "*", from = "*")` so it can handle any state transition defined in the model without requiring transition-specific handler methods.

### Event Types

The gateway processes three types of events:

* **CONNECT** - A new application instance connects and reports its initial shard states. The gateway creates a `HelixGatewayParticipant` and registers it with ZooKeeper.
* **UPDATE** - An application instance reports the result of a state transition. The gateway completes the pending `CompletableFuture` to unblock the state model handler.
* **DISCONNECT** - An application instance disconnects. The gateway disconnects the `HelixManager` and removes the participant from its registry.

### Running Tests

To run the helix-gateway unit and integration tests:

```
cd helix
mvn test -pl helix-gateway
```

Key test classes include:

* `TestGatewayServiceManager` - Tests event dispatching and participant lifecycle
* `TestGatewayServiceConnection` - Tests gRPC connection handling
* `TestHelixGatewayParticipant` - Tests state transition processing
* `TestHelixGatewayServicePollModeChannel` - Tests poll mode channel behavior
* `TestFilePullChannelE2E` - End-to-end integration test for file-based poll mode
* `TestGatewayCurrentStateCache` - Tests current/target state cache operations
