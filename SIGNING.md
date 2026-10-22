# Signed release builds on GitHub

1. Create a keystore ONCE and keep it (and its passwords) somewhere safe. If you lose it you can never update the app.
   ```
   keytool -genkeypair -v -keystore mediavault-release.jks -alias mediavault \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Base64-encode it: `base64 -w0 mediavault-release.jks` (macOS: `base64 -i mediavault-release.jks`).
3. In the GitHub repo: Settings -> Secrets and variables -> Actions -> New repository secret:
   - `KEYSTORE_BASE64`  (the base64 text)
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`  (mediavault)
   - `KEY_PASSWORD`
4. Push. The workflow builds debug + release on every push. Without the secrets the release APK is built UNSIGNED
   (named `...-UNSIGNED-...`) and cannot be installed until signed.
5. Tag a version (`git tag v0.1.0 && git push origin v0.1.0`) to also publish the APKs/AAB as a GitHub Release.

Never commit the .jks file or the passwords (`.gitignore` already blocks `*.jks`). Secrets are not exposed to pull
requests from forks.
