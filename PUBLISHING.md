# Publishing Atlantis Android

## Maven Central

Use JDK 17 and Android SDK Platform 34. You need access to the [verified `com.proxyman` namespace](https://central.sonatype.org/register/namespace/) and a primary signing key whose public key is on a [supported keyserver](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key).

1. Update `VERSION_NAME` and increment `VERSION_CODE` in `gradle.properties`. From the merged commit, create a local tag matching the version without a `v` prefix and build from a clean checkout of that tag.
2. Run the tests and build the signed ZIP. Set `RELEASE_KEY_FINGERPRINT` to your primary key's fingerprint and supply `MAVEN_SIGNING_PASSWORD` for a protected key. If you use [Gradle signing properties](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials), skip the export.

   ```bash
   ./gradlew :atlantis:test
   export MAVEN_SIGNING_KEY="$(gpg --armor --export-secret-keys "$RELEASE_KEY_FINGERPRINT")"
   ./gradlew --no-daemon :atlantis:bundleMavenCentralRelease
   ```

3. Upload `atlantis/build/distributions/atlantis-android-<version>-central.zip` with **Publish Component** in [Central Portal](https://central.sonatype.com/publishing/deployments). Once validated, push the tag upstream and select **Publish**. Use a new version and tag if a validation fix changes the tagged source.
4. Verify the published POM and AAR in [Maven Central](https://repo.maven.apache.org/maven2/com/proxyman/atlantis-android/).

`./publish.sh --version 1.0.1` is an alternative that builds the ZIP and pushes the version commit and tag to `origin` before you upload it. If the push fails, retry pushing the existing refs. Add `--target both` to also create a GitHub Release using the authenticated GitHub CLI configured for `origin`.

Use `./gradlew :atlantis:publishToMavenLocal` to check packaging without a signing key.

### Snapshots

[Enable snapshots](https://central.sonatype.org/publish/publish-portal-snapshots/#enabling-snapshot-releases-for-your-namespace) for `com.proxyman`. Configure signing as above and set `ossrhUsername` and `ossrhPassword` to the Portal token credentials in your user Gradle properties.

```bash
./gradlew :atlantis:publishReleasePublicationToSonatypeRepository -PVERSION_NAME=1.0.1-SNAPSHOT
```

## JitPack

Push the tag and use its exact name as the version. Check the build on [JitPack](https://jitpack.io/#ProxymanApp/atlantis-android).
