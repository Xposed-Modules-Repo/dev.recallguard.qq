# Third-party notices

## Historical Dobby reference

- Project: https://github.com/jmpews/Dobby
- Vendored commit: `c1da0315d7a2069bde2ab432e45d4ab6df91237a`
- License: Apache License 2.0
- License: Apache License 2.0; see the upstream project

Dobby was used by v1.2.x. The v1.3.0 CMake graph does not compile or link it; the APK uses LSPosed Modern Native API `hook_func/unhook_func`. Its unused vendor tree was removed from the cleaned v1.3.0 workspace and source archive.

## Xposed APIs

The historical local API 82 jar was removed. The current build resolves `io.github.libxposed:api:102.0.0` as `compileOnly`; the API artifact is not packaged into the APK. LSPosed supplies the runtime API.

## Research reference

QAuxiliary was inspected only to confirm QQ recall protocol fields, the native locator JSON contract, and the QQ 9.2.60 native signatures. Its application source tree, feature framework, identifiers, resources, module entrypoints, and packaged artifacts are not included in this project or APK.
