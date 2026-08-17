# EIP-8025 execution witness — port to `glamsterdam-devnet-8`

Branch: `glamsterdam-devnet-8-zkevm` (from `upstream/glamsterdam-devnet-8` @ `4654f965c9`)
Commit: `d1a52bd0a4` — 34 files, +1531/−63
Date: 2026-08-17

## Scope

1. Branch from `glamsterdam-devnet-8`.
2. Port the witness PR (pipeline + `debug_executionWitness` RPC).
3. Apply the devnet-7 fixes.
4. Upgrade zkEVM fixtures to `tests-zkevm@v0.8.0`.

## Result

zkEVM suite on devnet-8 + v0.8.0: **25,104 tests, 381 failed, 129 skipped**.

| Category | Count | Notes |
|---|---:|---|
| `Block creation failed unexpectedly` | 326 | same as devnet-7 baseline |
| Block mismatch (`expected: Block{header=…}`) | 41 | pre-existing devnet-8/v0.8.0 gap |
| `Block rejected for wrong reason` | 8 | was 7 on devnet-7 |
| `Error parsing test case file` | 6 | `NumberFormatException`, v0.8.0 format change |
| **Witness-assertion failures** | **0** | `state` / `codes` / `headers` all match |

For comparison, the devnet-7 baseline was 333 = 326 + 7, also with zero witness failures.

The 41 block mismatches and 6 parse errors are **not** witness bugs and were deliberately left
alone — they are devnet-8/v0.8.0 issues that predate this work.

## Strategy

Sequential cherry-pick was abandoned: the first of 18 commits produced 5 conflicts, and later
commits (`simplify`, `spotless`, review-fix commits) re-touch the same regions, so the same
conflicts would have to be resolved repeatedly. Instead the **net diff** was applied once
(`git diff 00d2f049d2 temp/witness-zkevm-baseline | git apply -3`) and the resulting 10 conflicts
resolved against devnet-8's code.

devnet-8 and `main` share merge-base `98b72450a3`; neither is an ancestor of the other. devnet-8 is
347 commits ahead of devnet-7 on that line.

## Adaptations to devnet-8

devnet-8 had diverged substantially from devnet-7.

### `AbstractCallOperation`
- devnet-8 keeps a **private** `refundCallNewAccountStateGas` (main's shape), not devnet-7's
  `gasCalculator().stateGasCostCalculator()` form.
- devnet-8 already charges new-account state gas earlier in the call path, so temp's
  `chargeCallNewAccountStateGas` block was dropped — only the witness `addCodeRead` hook was added.
- The devnet-7 refund fix still applied: the charge uses `recipientAddress` (`address(frame)`) but
  the refund used `childFrame.getContractAddress()`. For CALLCODE the recipient is the caller while
  the contract address is the code target, so the refund undid a charge that was never made and
  drove state gas negative. Now `childFrame.getRecipientAddress()`.

### `MainnetTransactionProcessor`
- `chargeIntrinsicStateGas` → `chargeTopFrame`, returning `PrepCharges`; the recipient touch and
  entry charge moved **inside** it. devnet-7's `authChargeHalted` / `chargeTransactionEntry` block
  at the call site is superseded and was not imported.
- `codeReadTracker` threaded through `chargeTopFrame` → `chargeCodeDelegationAccesses`.
- `Eip8037Trace` does not exist on devnet-8 — import dropped.

### Bonsai renames / moves
| devnet-7 | devnet-8 |
|---|---|
| `…bonsai.cache.CodeCache` | `…common.code.PathBasedCodeCache` |
| `NoopBonsaiCachedMerkleTrieLoader` | `…worldview.accumulator.preload.NoOpBonsaiCachedMerkleTrieLoader` |
| `NoOpBonsaiCachedWorldStorageManager` | `…worldview.cache.NoOpBonsaiWorldStateCacheManager` |
| `ethereum.core.MutableWorldState` | `plugin.services.worldstate.MutableWorldState` |
| `…bonsai.worldview.BonsaiWorldStateUpdateAccumulator` | `…bonsai.worldview.accumulator.…` |

The `BonsaiWorldState` constructor arity is unchanged; only the types/packages moved.

### Blob schedule
devnet-8 already has fixture blob-schedule support via #10852 (`9b5830ea79`), which
`temp/witness-zkevm-baseline` does **not** contain — temp carried its own equivalent. devnet-8's
implementation was kept and temp's duplicate `SpecConfig` class, field, and constructor parameter
were removed. `StubGenesisConfigOptions` likewise kept devnet-8's `Optional`-typed field.

### Reference-test world state
`buildProtocolContext` keeps devnet-8's `storageConfiguration` parameter **and** temp's `+ 1` cache
layer, so the parent world state stays resident for the witness build.

## Bug found by the v0.8.0 fixtures

Test: `test_reservoir_settlement_by_failure_point[…failure_point_set_delegation_oog]`

Symptom: witness `codes` had 7 entries vs 6 expected; the extra was a 7702 delegation designator
(`0xef0100c4028cd4…`) for the transaction recipient.

Cause: the ported code recorded the recipient's designator read in `processTransaction`, *before*
`chargeTopFrame`. devnet-8 defers the recipient load until the authorization charges clear
(`if (!outOfGas)`), so an authorization out-of-gas means the recipient is never read — and its code
must not appear in the witness.

Fix: moved the record inside `chargeTopFrame`, immediately after
`initialFrame.getEip7928AccessList().ifPresent(bal -> bal.addTouchedAccount(to))`. 382 → 381.

> **Rule:** record a witness code read at the *same point* the block access list touches the
> account — never earlier. This is the same principle as recording authorization reads inside the
> per-authority charge replay (which stops at the first OOG) rather than in
> `CodeDelegationProcessor` (which processes every authorization unconditionally).

## Fixture upgrade

`ethereum/referencetests/build.gradle`: `tests-zkevm@v0.6.2` → `tests-zkevm@v0.8.0`.

`gradle/verification-metadata.xml`: added

```
ethereum:execution-specs:tests-zkevm@v0.8.0 / execution-specs-tests-zkevm@v0.8.0-fixtures_zkevm.tar.gz
sha256 = f19f782fb5cefb7466e852e101d28883452221f03cfa1867eec5d520a3112c5c
```

Checksum computed from the actual release artifact
(`https://github.com/ethereum/execution-specs/releases/download/tests-zkevm@v0.8.0/fixtures_zkevm.tar.gz`,
549,434,596 bytes), not copied or guessed.

## Outstanding

- **6 fixture parse errors** (`NumberFormatException`) — v0.8.0 appears to have changed a field
  format. Worth a separate look; not investigated here.
- **41 block mismatches** — pre-existing devnet-8/v0.8.0 gap, unrelated to the witness pipeline.
- The commit is **unsigned**: `git commit -S` fails because gpg is not installed on this machine
  (`cannot run gpg: No such file or directory`). Re-sign with
  `git commit -S --amend --no-edit` once available.
- `ethereum/referencetests/build.gradle` fails `spotlessGroovyGradleCheck` — pre-existing, inherited
  from the devnet-7 branch, will fail CI if that check runs.

## Related

- `temp/witness-zkevm-baseline` — devnet-7 line, 333 failures (326 + 7), zero witness failures.
- `refactor/witness-no-tracer` — devnet-7 reference implementation, also 333.
- `feat/debug-execution-witness` — the main-based PR. The devnet-7/8 fixes are **not** portable
  there: `chargeCodeDelegationAccesses` and `AuthorityAccess` do not exist on `main`, and without a
  per-authority charge replay the current placement is correct for the forks `main` supports.
