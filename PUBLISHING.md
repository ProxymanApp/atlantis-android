# Publishing Atlantis Android

## Maven Central

A maintainer needs access to the [verified `com.proxyman` namespace](https://central.sonatype.org/register/namespace/) and a primary signing key whose public key is on a [supported keyserver](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key).

1. Have a repository admin create the `maven-central` GitHub Actions environment upstream and restrict it to `main`. Add `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` as environment secrets with the [Central Portal token credentials](https://central.sonatype.org/publish/generate-portal-token/). Add `MAVEN_SIGNING_KEY` for the ASCII-armored private key and `MAVEN_SIGNING_PASSWORD` for its passphrase if protected.
2. Merge a new `VERSION_NAME` and incremented `VERSION_CODE` in `gradle.properties`. Tag the resulting commit on `main` with the exact version without a `v` prefix, such as `1.0.1`, and push the tag upstream.
3. After the workflow is merged, a maintainer with write access runs **Publish to Maven Central** from `main` in GitHub Actions and enters the tag. The workflow tests, signs, and uploads the bundle. Sonatype publishes it automatically after validation. The workflow waits for Sonatype to confirm publication and includes the deployment ID in the run summary.

If the workflow cannot confirm publication, check the deployment in [Central Portal](https://central.sonatype.com/publishing/deployments) before retrying. Find it by ID or by the name `atlantis-android-<tag>`. Use a new version and tag if validation requires a source change.

### Local bundle

From a clean release tag, use JDK 17 and Android SDK Platform 34. Configure the signing key through `MAVEN_SIGNING_KEY` and `MAVEN_SIGNING_PASSWORD` or [Gradle signing properties](https://docs.gradle.org/current/userguide/signing_plugin.html#sec:signatory_credentials).

```bash
./gradlew :atlantis:test
./gradlew --no-daemon :atlantis:bundleMavenCentralRelease
```

You can upload `atlantis/build/distributions/atlantis-android-<version>-central.zip` in Central Portal. To check Maven packaging without a signing key, run `./gradlew :atlantis:publishToMavenLocal`.

### Snapshots

[Enable snapshots](https://central.sonatype.org/publish/publish-portal-snapshots/#enabling-snapshot-releases-for-your-namespace) for `com.proxyman`. Configure signing as above and set `ossrhUsername` and `ossrhPassword` to the Portal token credentials in your user Gradle properties.

```bash
./gradlew :atlantis:publishReleasePublicationToSonatypeRepository -PVERSION_NAME=1.0.1-SNAPSHOT
```

## JitPack

Push the tag and use its exact name as the version. Check the build on [JitPack](https://jitpack.io/#ProxymanApp/atlantis-android).
