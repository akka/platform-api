# Kalix Service Instance Types

`instance_types.json` in this directory is the canonical registry of Kalix
service instance types. The single source of truth shared by:

- The Go deployment operator (embedded into the
  `serviceinstancetype` package via `go:embed`; refresh the operator-package
  copy with `make -C operators sync-instance-types` after editing the file
  here).
- The Scala management-api (loaded from the classpath at
  `kalix/platform/instance_types.json`; `build.sbt` mounts `api/` as an
  unmanaged resource directory).

## Schema

```jsonc
{
  // File-level JVM defaults applied to every container that does not pin
  // its own equivalent. heapPercentage and minActiveProcessors are
  // required — there is no fallback to Go-hardcoded values.
  // directMemoryPercentage is optional: absent means no direct-memory
  // cap is emitted unless a container pins its own jvm.directMemorySize
  // or jvm.directMemoryPercentage.
  "jvmDefaults": {
    "heapPercentage":      50,   // -XX:MaxRAMPercentage / -Xmx target
    "minActiveProcessors": 2     // floor for -XX:ActiveProcessorCount;
                                 //   effective value = max(this, ceil(cpu/1000))
  },
  "entries": [
    {
      "displayName":     "g1.general.small",
      "status":          "active",        // "active" | "deprecated"
      "platform_default": true,           // exactly one entry must carry this
      "replacement":     "g1.general.small", // required when status=deprecated
      "sidecar": {
        "runtime": { "cpu": "400m", "memory": "1664Mi",
                     "jvm": { "directMemorySize": "416Mi" } },
        "userFn":  { "cpu": "200m", "memory": "1280Mi",
                     "jvm": { "directMemorySize": "256Mi" } }
      },
      "embedded": { "runtime": { "cpu": "800m", "memory": "2560Mi",
                                 "jvm": { "directMemorySize": "640Mi" } } }
    }
  ]
}
```

### Per-container `jvm` override (all fields optional)

| Field | Effect |
|---|---|
| `heapPercentage` | overrides `jvmDefaults.heapPercentage` |
| `xms`, `xmx` | absolute heap; mutually exclusive with `heapPercentage` and must be equal to each other |
| `directMemoryPercentage` | overrides `jvmDefaults.directMemoryPercentage` |
| `directMemorySize` | absolute direct memory; mutually exclusive with `directMemoryPercentage` |
| `minActiveProcessors` | per-container floor on `-XX:ActiveProcessorCount`; effective value is `max(this, ceil(cpu_millis / 1000))`. Absent on a container = falls through to `jvmDefaults.minActiveProcessors`. |

The Go operator resolves these into `-Xms` / `-Xmx` (or
`-XX:MaxRAMPercentage` for embedded mode), `-XX:MaxDirectMemorySize`, and
`-XX:ActiveProcessorCount` at registry init time. Per-service
`KalixServiceConfig` ConfigMaps **cannot** override JVM tuning — the
relevant Go struct fields are tagged `yaml:"-"` so any
`heapPercentage` / `maxDirectMemorySize` / `minActiveProcessors` /
`activeProcessors` keys present in a customer's `config.yaml` are
silently dropped and overwritten on every reconcile. Sizing is a
platform decision, settled at compile time in this file.

The Scala management-api ignores `jvmDefaults` and the per-container `jvm`
block: resource numbers are operator-side concerns. Only `displayName`,
`status`, `replacement`, and `platform_default` flow through the
management-api's parser.

## Lifecycle

- **Adding a tier**: append a new entry to `entries`. Choose a canonical
  `displayName` of the form `g<N>.<shape>.<size>` (e.g.
  `g1.compute.medium`); set `status: active`; populate `sidecar` and
  `embedded` resource numbers. Per-container `jvm` overrides are optional
  — omitting them inherits the file-level `jvmDefaults`.
- **Deprecating a tier**: change `status` from `active` to `deprecated` and
  add a `replacement` field pointing at another entry's `displayName`. The
  webhook will reject new applies of the deprecated type; existing services
  pinned to it continue to reconcile and surface a
  `KalixInstanceTypeDeprecated` condition.
- **Retiring a tier**: remove the row entirely. Only do this after a
  cluster-by-cluster audit confirms no live `KalixService` references it
  (in any equivalent input form, including the legacy bare and `small`
  aliases).
- **Tuning JVM defaults**: edit `jvmDefaults` (file-level) or add a
  per-container `jvm` block on individual entries. The Go operator validates
  the result at init: percentage out of (0,100) panics; `heapPercentage` +
  `directMemoryPercentage` exceeding 90 logs a warning per offender (for
  the slack required by metaspace, code cache, thread stacks, native libs).

## The `platform_default` marker

Exactly one active entry must carry `"platform_default": true`. Two
consumers read it:

- The Scala management-api seeds `defaultInstanceType` on a
  freshly-created `KalixOrganization` from this entry, so the platform's
  default tier shows up explicitly in `kubectl get kalixorganization -o yaml`
  rather than being implicit operator behaviour.
- The deployment operator uses it as the last-resort fallback in two
  places: the resolver (when neither `KalixService.spec.resources.instanceType`
  nor an org-config `defaultInstanceType` is set, e.g. the post-upgrade
  window before `KalixOrganization` has synced), and the webhook (the
  only tier a customer may pin explicitly when no org allowlist is
  configured).

The marker may move between active entries across PRs. CI rejects zero,
multiple, or deprecated platform_default entries.

## Invariants enforced by CI

- `displayName` once added must never be removed except via explicit retirement.
- `status` only transitions forward: `active → deprecated → (entry removed)`.
- `displayName` matches `^(small|<shape>.<size>|g<N>.<shape>.<size>)$`. The
  legacy bare and `small` forms are accepted on input for backward
  compatibility but the canonical form in this file is the prefixed one.
- Exactly one active entry has `"platform_default": true`.
- Operator-package copy of this file does not drift (`make verify-instance-types-sync`).

The CI evolution check lives at `operators/hack/check-instance-types-evolution`.
