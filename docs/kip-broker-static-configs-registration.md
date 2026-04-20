# KIP: Broker Reports Static Configurations During Registration

## Status

**Current state**: Under Discussion  
**Discussion thread**: TBD  
**JIRA**: TBD  
**Author**: TaiJu Wu  

---

## Motivation

Kafka's controller currently has no visibility into each broker's static configuration.
When a broker registers with the controller via `BrokerRegistrationRequest`, it reports
its supported feature versions and listener information, but not its static configuration
values. This creates a fundamental gap that blocks two categories of improvements:

### Aligning `incrementalAlterConfigs` validation (KIP-1256)

The controller and broker currently behave differently when processing
`Admin.incrementalAlterConfigs` requests. One key inconsistency is that brokers
immediately reject invalid dynamic configuration values, while the controller silently
drops them. To replicate broker-side validation logic, the controller needs each broker's
static configuration as context — certain dynamic configuration constraints depend on the
static values present on that broker. Without this information, the controller cannot
perform faithful broker-equivalent validation.

### Enforcing configuration constraints during MetadataVersion upgrades (KIP-1294)

As the cluster's `MetadataVersion` advances, new constraints on configuration values may
come into effect. Without knowledge of each broker's static configuration, the controller
cannot proactively verify that all brokers satisfy the constraints of the target version
before allowing an upgrade to proceed. The incompatibility can only be discovered after
the fact, when a broker fails on restart.

This KIP addresses the root cause shared by both problems: the controller lacks broker
static configuration data. We propose that brokers include their non-sensitive static
configurations in `BrokerRegistrationRequest`, and that the controller persists this
information in `RegisterBrokerRecord` so it survives controller failovers. This KIP
intentionally contains no validation logic — it provides only the infrastructure that
KIP-1256 and KIP-1294 build upon.

---

## Public Interfaces

### `BrokerRegistrationRequest` — new version 6

A new tagged field `StaticConfigs` is added in version 6. Each entry contains the name
and value of a non-sensitive broker static configuration.

```json
// Version 6 adds StaticConfigs for KIP-XXXX
{ "name": "StaticConfigs", "type": "[]StaticConfig",
  "versions": "6+", "taggedVersions": "6+", "tag": "1",
  "about": "Non-sensitive static broker configurations reported at registration time.",
  "fields": [
    { "name": "Name",  "type": "string", "versions": "6+", "mapKey": true,
      "about": "The configuration key." },
    { "name": "Value", "type": "string", "versions": "6+", "nullableVersions": "6+",
      "about": "The configuration value, or null if not set." }
  ]
}
```

### `RegisterBrokerRecord` — new version 5

A corresponding `StaticConfigs` field is added to the metadata record so the controller
can reconstruct broker configuration state after a failover.

```json
// Version 5 adds StaticConfigs for KIP-XXXX
{ "name": "StaticConfigs", "type": "[]BrokerStaticConfig",
  "versions": "5+", "taggedVersions": "5+", "tag": "2",
  "about": "Non-sensitive static broker configurations stored at registration time.",
  "fields": [
    { "name": "Name",  "type": "string", "versions": "5+", "mapKey": true },
    { "name": "Value", "type": "string", "versions": "5+", "nullableVersions": "5+" }
  ]
}
```

### New `MetadataVersion`

A new `MetadataVersion` entry (e.g., `IBP_4_4_IV1`) will gate the use of the new
protocol versions. When the cluster is running at or above this version:

- Brokers send `BrokerRegistrationRequest` v6 with `StaticConfigs` populated.
- The controller writes `RegisterBrokerRecord` v5 with `StaticConfigs` persisted.
- `MetadataVersion.registerBrokerRecordVersion()` returns `5`.

---

## Proposed Changes

### 1. Broker side — `BrokerLifecycleManager`

`sendBrokerRegistration()` is updated to collect non-sensitive static configuration
entries from `KafkaConfig` and include them in the request when the negotiated protocol
version is 6 or above.

Sensitive configurations (those with `ConfigDef.Type.PASSWORD`) are excluded. Only
broker-scoped static configurations are included; dynamic configurations and per-topic
configurations are out of scope.

```java
// Pseudocode — actual implementation in BrokerLifecycleManager
if (requestVersion >= 6) {
    BrokerRegistrationRequestData.StaticConfigCollection staticConfigs =
        new BrokerRegistrationRequestData.StaticConfigCollection();
    kafkaConfig.nonSensitiveStaticConfigs().forEach((k, v) ->
        staticConfigs.add(new BrokerRegistrationRequestData.StaticConfig()
            .setName(k)
            .setValue(v == null ? null : v.toString()))
    );
    data.setStaticConfigs(staticConfigs);
}
```

### 2. Controller side — `ClusterControlManager.registerBroker()`

When receiving a `BrokerRegistrationRequest` v6 and the current `MetadataVersion`
supports static configs, the controller copies the `StaticConfigs` field into the
`RegisterBrokerRecord`.

No validation logic is performed at this layer — the record merely stores the data as
reported by the broker.

```java
// Pseudocode — actual implementation in ClusterControlManager
if (featureControl.metadataVersionOrThrow().isAtLeast(STATIC_CONFIG_MV)) {
    record.setStaticConfigs(request.staticConfigs());
}
```

### 3. Controller side — `ClusterControlManager.replay(RegisterBrokerRecord)`

The in-memory `BrokerRegistration` object is updated to carry the static configs so they
are available to higher-level logic (KIP-1256, KIP-1294) without requiring another
request to the broker.

### 4. `MetadataVersion`

A new `IBP_4_4_IV1` entry is added. `registerBrokerRecordVersion()` is updated to return
`(short) 5` when the version is at or above `IBP_4_4_IV1`.

---

## Compatibility, Deprecation, and Migration Plan

**Wire compatibility**

`StaticConfigs` is added as a tagged field in both the request and the record. Brokers
running an older version that does not support version 6 of `BrokerRegistrationRequest`
will simply not send the field. The controller will accept registrations from both old
and new brokers gracefully.

**Sensitive data**

Configurations with `ConfigDef.Type.PASSWORD` are never included. This ensures no
credentials or secrets are transmitted over the wire or stored in the metadata log.

**No deprecation required**

This is a purely additive change. No existing fields are removed or modified.

**Rolling upgrade**

During a rolling upgrade, some brokers will send static configs and others will not.
The controller stores an empty map for brokers that do not send the field. This is
safe because downstream consumers (KIP-1256, KIP-1294) are not introduced in this KIP
and will only be enabled once all brokers in the cluster have been upgraded.

---

## Rejected Alternatives

### In-memory only (no persistence in metadata log)

Storing static configs only in memory (not in `RegisterBrokerRecord`) would be simpler,
but the controller would lose this information on failover. The new controller leader
would have to wait for each broker to re-register before recovering the data, creating a
window during which validation logic could not operate. Persisting to the metadata log
eliminates this window.

### Include sensitive configurations with encryption

Encrypting sensitive configs before including them in the registration request was
considered but rejected. The complexity of key management and the risk of key rotation
failures outweigh the benefit. Validation logic that requires sensitive config values
must use alternative mechanisms defined in those respective KIPs.

### Push configs via a separate RPC

Using a dedicated `ReportBrokerConfigsRequest` instead of extending
`BrokerRegistrationRequest` was considered. This would decouple the concerns, but it
introduces an additional round-trip and makes it harder to guarantee that the controller
has the config data at the point the broker is considered registered. Bundling the data
into the existing registration request ensures atomicity.
