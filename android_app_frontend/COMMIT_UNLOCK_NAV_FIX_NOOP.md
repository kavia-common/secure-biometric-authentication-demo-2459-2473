# Unlock → Home navigation fix commit note (noop)

Requested commit message: **"Fix unlock navigation to Home"**

No new commit was created because there are **no source changes pending** in the working tree. The only modified files were generated artifacts under `.knowledge/**`, which were reverted and are not appropriate to commit.

Verification:
- The navigation reliability fix (suppressing the next `onResume()` auto-lock immediately after biometric/device-credential success) is already present in the current `HEAD` version of:
  - `android_app_frontend/app/src/main/kotlin/org/example/app/MainActivity.kt`
- Working tree is clean after reverting `.knowledge/**`.

If you still need a commit with that exact message, please ensure the intended code changes are present as uncommitted source diffs (not generated artifacts), then re-run the commit step.
