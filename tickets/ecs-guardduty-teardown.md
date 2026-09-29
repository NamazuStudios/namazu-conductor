# Ticket: Re-enable `EcsOrchestrationServiceIT` — GuardDuty security group teardown fix and CI harness

**Status:** implemented
**Components:** `ecs` (IT), CI workflows, `edgegap` (IT)
**Issue:** https://github.com/NamazuStudios/namazu-conductor/issues/35

## Summary

`EcsOrchestrationServiceIT` was disabled wholesale in 2026: AWS GuardDuty's EC2 Runtime
Monitoring auto-injects a `GuardDutyManagedSecurityGroup-*` into the test VPC whenever an
EC2-launch-type task runs, CloudFormation can't delete a group it doesn't own, and a VPC can't be
deleted while a non-default security group remains — so the stack's `Vpc` resource wedged in
`DELETE_IN_PROGRESS` forever and the *next* run's stack creation failed on a name collision.
Disabling every test also skipped `@BeforeClass`/`@AfterClass`, which conveniently meant no stack
was created at all — and just as conveniently meant the suite proved nothing. This ticket is the
fix at every teardown path, plus the re-enable, plus the hardening items from the issue's comments.

## The teardown fix

An EC2 security group can't be deleted while ENIs are attached, and a just-stopped EC2 task's ENIs
take minutes to detach while its instance terminates — the prior scrub deleted (and retried) the
SGs directly, but nothing waited for the ENIs, so the retries could exhaust while the instance was
still terminating and proceed to a doomed stack deletion. Now every unmanaged security group (not
`default`, not owned by the stack) is deleted only after its attached ENIs are gone:

- **`EcsOrchestrationServiceIT.scrubVpcDependencies`** — new
  `waitForSecurityGroupEnisToDetach` polls `describeNetworkInterfaces` filtered by `group-id`
  (5-minute cap; on timeout, deletion is still attempted so a slow detach degrades rather than
  deadlocks), ahead of the existing 6×10s delete-retry loop.
- **`ecs/cloudformation/stop-integration-test-stack.sh`** — previously just called
  `cloudformation delete-stack` with no scrub at all; now finds the stack's VPC by its `Name` tag,
  performs the same unmanaged-SG scrub (ENI wait, then delete with retries, `InvalidGroup.NotFound`
  treated as success), and only then triggers the CloudFormation deletion. Kept in step with the
  Java logic by hand — flagged in the script header.
- Both scripts now treat `AWS_PROFILE` as **optional** (unset/empty → environment credentials,
  no `--profile` flag), so the same scripts run locally (profile default `namazu-internal`) and in
  CI (env vars from workflow secrets).

## Re-enable

All nine `@Test(enabled = false)` annotations on `EcsOrchestrationServiceIT` are removed and the
per-suite disable comment gone. The suite once again creates the `conductor-integration-test`
stack, runs the Fargate/EC2-spot/daemon/metadata tests against it, scrubs, and tears down.

## CI harness (from the issue's comments)

- **`ECS Test Harness` workflow** (`.github/workflows/ecs-harness.yaml`): a manual
  `workflow_dispatch` with a `start`/`stop` choice runs the same start/stop scripts with
  environment credentials — the CI-side equivalent of the local persistent-stack workflow from
  the issue's cost-analysis comment. Shares the `conductor-ecs-it` concurrency group with the
  IT workflow so harness actions and test runs never overlap on the same named stack.
- This does *not* switch routine CI to a persistent stack — every IT run still creates and deletes
  its own stack by default (clean state per run, no idle cost between merges). The harness is for
  deliberate stand-up when someone wants the stack running across a work session.

## EdgeGap flake hardening (from the issue's comments)

`EdgeGapOrchestrationServiceIT.deployNginxAndVerifyHelloWorld` failed transiently twice with the
deployment `RUNNING` but its edge ingress stuck at 403 past the test's own 60s propagation window,
passing cleanly on manual rerun both times. The test now carries a bounded
`DeploymentFlakeRetryAnalyzer` (TestNG `IRetryAnalyzer`, one extra attempt): a real-cloud flake
self-heals instead of paging someone, while a real regression still fails the suite after two
genuine attempts — and TestNG's per-attempt reporting keeps "passed on retry" visibly distinct
from a clean first-try pass. Because a retry creates a second deployment, teardown now tracks
*every* created execution id and stops them all, so a failed first attempt can't orphan a live
deployment in the account.

## Known limitations

- The bash scrub and the Java scrub are two implementations of one rule, kept in step by hand —
  the script can't share the JVM's logic. A drift would resurface as the original hang.
- The ENI-wait timeout is a judgment call (5 minutes). If an instance takes longer to terminate,
  deletion is attempted anyway and the existing delete-retries cover the tail.
- The JVM-side hang from the issue's second observation (job stuck after teardown with no AWS
  activity) was never reproduced after the GuardDuty fix; the issue's advice stands — if it
  recurs, capture a thread dump before cancelling.

## Testing

- Per repo convention, no local build: CI is the verdict. The ECS IT runs real infrastructure on
  push/PR; a green run with a real stack lifecycle (not the vacuous `Tests run: 0` of the disabled
  era) is the acceptance criterion for the re-enable.
- `bash -n` syntax-checks both scripts; workflow YAML reviewed by inspection.
- EdgeGap IT runs on the same push/PR cycle; the retry analyzer is exercised only on a transient
  failure, so the common path is unchanged.
