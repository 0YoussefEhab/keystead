# Third-party notices

Keystead bundles the following open-source software.

## argon2-browser 1.18.0

Argon2 password hashing compiled to WebAssembly, embedded in `MyVault.html`.
https://github.com/antelle/argon2-browser

```
Copyright © 2021 Antelle

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

The Argon2 reference implementation it is built from is dual-licensed CC0 / Apache 2.0 (https://github.com/P-H-C/phc-winner-argon2).

## Runtime dependencies (installed at build time, not bundled in this repo)

- pywebview (BSD-3-Clause), PyInstaller (GPL-2.0 with bootloader exception)
- AndroidX AppCompat, WebKit, Biometric (Apache 2.0)
