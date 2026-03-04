#!/usr/bin/env bash
# publish.sh — Publish Atlantis Android to JitPack and/or Maven Central.
#
# Usage:
#   ./publish.sh --version 1.2.0                            # publish to maven-central
#   ./publish.sh --target jitpack       --version 1.2.0
#   ./publish.sh --target maven-central  --version 1.2.0
#   ./publish.sh --target maven-central  --version 1.2.0 --skip-git
#   ./publish.sh --version 1.2.0 --dry-run
#
# Flags:
#   --target   jitpack | maven-central | both      (optional, defaults to maven-central)
#   --version  Semver string                   (required, e.g. 1.2.0 or 1.2.0-SNAPSHOT)
#   --skip-git Skip commit/tag/release actions     (optional)
#   --dry-run  Skip destructive actions             (optional)

set -euo pipefail

# ---------------------------------------------------------------------------
# Colors
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
step_num=0

step() {
    step_num=$((step_num + 1))
    echo -e "\n${CYAN}${BOLD}[Step ${step_num}]${NC} ${BOLD}$1${NC}"
}

info() {
    echo -e "  ${GREEN}✓${NC} $1"
}

warn() {
    echo -e "  ${YELLOW}⚠${NC} $1"
}

fail() {
    echo -e "  ${RED}✗ ERROR:${NC} $1" >&2
    exit 1
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
TARGET=""
VERSION=""
SKIP_GIT=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            TARGET="$2"
            shift 2
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --skip-git)
            SKIP_GIT=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [--target <jitpack|maven-central|both>] --version <SEMVER> [--skip-git] [--dry-run]"
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

# Default to "maven-central" when --target is omitted
if [[ -z "$TARGET" ]]; then
    TARGET="maven-central"
fi

if [[ "$TARGET" != "jitpack" && "$TARGET" != "maven-central" && "$TARGET" != "both" ]]; then
    fail "--target must be 'jitpack', 'maven-central', or 'both', got '$TARGET'"
fi

if [[ -z "$VERSION" ]]; then
    fail "--version is required (e.g. 1.2.0)"
fi

if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$'; then
    fail "Invalid version format '$VERSION'. Expected semver like 1.2.0 or 1.2.0-SNAPSHOT"
fi

# ---------------------------------------------------------------------------
# Resolve paths — script must run from atlantis-android/
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

GRADLE_PROPS="gradle.properties"

if [[ ! -f "$GRADLE_PROPS" ]]; then
    fail "Cannot find $GRADLE_PROPS. Are you in the atlantis-android directory?"
fi

if [[ ! -f "gradlew" ]]; then
    fail "Cannot find gradlew. Are you in the atlantis-android directory?"
fi

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD} Atlantis Android — Publish${NC}"
echo -e "${BOLD}========================================${NC}"
echo -e "  Target:   ${CYAN}${TARGET}${NC}"
echo -e "  Version:  ${CYAN}${VERSION}${NC}"
echo -e "  Skip git: ${CYAN}${SKIP_GIT}${NC}"
echo -e "  Dry run:  ${CYAN}${DRY_RUN}${NC}"
echo ""

# ---------------------------------------------------------------------------
# Step 1: Validate prerequisites
# ---------------------------------------------------------------------------
step "Validating prerequisites"

command -v java >/dev/null 2>&1 || fail "'java' not found. Install JDK 17+."
info "java found: $(java -version 2>&1 | head -1)"

if [[ "$TARGET" == "jitpack" || "$TARGET" == "both" ]]; then
    command -v gh >/dev/null 2>&1 || fail "'gh' (GitHub CLI) not found. Install via: brew install gh"
    info "gh found: $(gh --version | head -1)"
fi

if [[ "$SKIP_GIT" == false ]]; then
    command -v git >/dev/null 2>&1 || fail "'git' not found."
    info "git found: $(git --version)"
fi

if [[ "$TARGET" == "maven-central" || "$TARGET" == "both" ]]; then
    command -v gpg >/dev/null 2>&1 || fail "'gpg' not found. Install via: brew install gnupg"
    info "gpg found: $(gpg --version | head -1)"
fi

# Cross-platform in-place replace utility (BSD/GNU sed compatibility).
replace_line() {
    local pattern="$1"
    local replacement="$2"
    local file="$3"

    if sed --version >/dev/null 2>&1; then
        sed -i "s|^${pattern}=.*|${replacement}|" "$file"
    else
        sed -i '' "s|^${pattern}=.*|${replacement}|" "$file"
    fi
}

# ---------------------------------------------------------------------------
# Step 2: Update version in gradle.properties
# ---------------------------------------------------------------------------
step "Updating version in $GRADLE_PROPS"

# Read current VERSION_CODE and increment
CURRENT_CODE=$(grep '^VERSION_CODE=' "$GRADLE_PROPS" | cut -d'=' -f2)
if ! [[ "$CURRENT_CODE" =~ ^[0-9]+$ ]]; then
    fail "Invalid VERSION_CODE in $GRADLE_PROPS: '$CURRENT_CODE'"
fi
NEW_CODE=$((CURRENT_CODE + 1))

# Replace VERSION_NAME
replace_line "VERSION_NAME" "VERSION_NAME=${VERSION}" "$GRADLE_PROPS"
# Replace VERSION_CODE
replace_line "VERSION_CODE" "VERSION_CODE=${NEW_CODE}" "$GRADLE_PROPS"

info "VERSION_NAME → ${VERSION}"
info "VERSION_CODE → ${NEW_CODE} (was ${CURRENT_CODE})"

# ---------------------------------------------------------------------------
# Step 3: Run unit tests
# ---------------------------------------------------------------------------
step "Running unit tests"

./gradlew :atlantis:test --no-daemon
info "All tests passed"

# ---------------------------------------------------------------------------
# Step 4: Build release AAR
# ---------------------------------------------------------------------------
step "Building release AAR"

./gradlew :atlantis:assembleRelease --no-daemon
info "Release AAR built successfully"

# ---------------------------------------------------------------------------
# Step 5: Publish to Maven Local (smoke test)
# ---------------------------------------------------------------------------
step "Publishing to Maven Local (smoke test)"

./gradlew :atlantis:publishToMavenLocal --no-daemon
GROUP_ID=$(grep '^GROUP=' "$GRADLE_PROPS" | cut -d'=' -f2)
ARTIFACT_ID=$(grep '^POM_ARTIFACT_ID=' "$GRADLE_PROPS" | cut -d'=' -f2)
M2_GROUP_PATH="${GROUP_ID//./\/}"
info "Published to Maven Local (~/.m2/repository/${M2_GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/)"

# ---------------------------------------------------------------------------
# Target-specific steps
# ---------------------------------------------------------------------------

# --- Maven Central: check creds + publish to Sonatype (before tagging) -----
if [[ "$TARGET" == "maven-central" || "$TARGET" == "both" ]]; then
    step "Validating Gradle Sonatype publish task"

    if ! ./gradlew :atlantis:tasks --all | grep -q "publishReleasePublicationToSonatypeRepository"; then
        fail "Task ':atlantis:publishReleasePublicationToSonatypeRepository' not found. Check atlantis/build.gradle.kts Sonatype repository name/config."
    fi
    info "Sonatype publish task is available"

    step "Checking Maven Central credentials"

    GRADLE_HOME_PROPS="$HOME/.gradle/gradle.properties"
    if [[ ! -f "$GRADLE_HOME_PROPS" ]]; then
        fail "~/.gradle/gradle.properties not found. See PUBLISHING.md for setup instructions."
    fi

    for key in ossrhUsername ossrhPassword signing.keyId signing.password signing.secretKeyRingFile; do
        if ! grep -Fq "${key}=" "$GRADLE_HOME_PROPS" 2>/dev/null; then
            fail "Missing '${key}' in ~/.gradle/gradle.properties"
        fi
    done
    info "All required credentials found in ~/.gradle/gradle.properties"

    step "Publishing to Sonatype Central API"

    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would publish artifacts to Sonatype Central API"
    else
        ./gradlew :atlantis:publishReleasePublicationToSonatypeRepository --no-daemon
        info "Published artifacts to Sonatype Central API"
    fi
fi

# --- Git: commit version bump, tag, push (shared, runs once) ---------------
if [[ "$SKIP_GIT" == false ]]; then
    step "Committing version bump"

    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would commit gradle.properties changes"
    else
        git add "$GRADLE_PROPS"
        git commit -m "chore: bump version to ${VERSION}"
        info "Committed version bump"
    fi

    step "Creating git tag v${VERSION}"

    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would create and push tag v${VERSION}"
    else
        git tag -a "v${VERSION}" -m "Release version ${VERSION}"
        git push origin HEAD
        git push origin "v${VERSION}"
        info "Tag v${VERSION} pushed to origin"
    fi
else
    step "Skipping git commit/tag (--skip-git)"
    info "No git commit/tag/push actions were executed"
fi

# --- JitPack: create GitHub release -----------------------------------------
if [[ "$TARGET" == "jitpack" || "$TARGET" == "both" ]]; then
    step "Creating GitHub release"

    if [[ "$SKIP_GIT" == true ]]; then
        warn "Skipping GitHub release because --skip-git is enabled"
    elif [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would create GitHub release v${VERSION}"
    else
        gh release create "v${VERSION}" \
            --title "v${VERSION}" \
            --generate-notes
        info "GitHub release v${VERSION} created"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""

if [[ "$TARGET" == "maven-central" || "$TARGET" == "both" ]]; then
    echo -e "${GREEN}${BOLD}Uploaded to Sonatype Central publishing API.${NC} Complete release in Central Portal:"
    echo -e "  1. Log in to ${CYAN}https://central.sonatype.com${NC}"
    echo -e "  2. Go to ${BOLD}Publishing → Deployments${NC}"
    echo -e "  3. Find your deployment for namespace ${BOLD}${GROUP_ID}${NC}"
    echo -e "  4. Click ${BOLD}Publish${NC} (or wait if auto-publish is enabled)"
    echo -e "  5. Artifacts sync to Maven Central in ~10-30 minutes"
    echo ""
    echo -e "  Verify: ${CYAN}https://repo1.maven.org/maven2/com/proxyman/atlantis-android/${VERSION}/${NC}"
    echo ""
fi

if [[ "$TARGET" == "jitpack" || "$TARGET" == "both" ]]; then
    echo -e "${GREEN}${BOLD}JitPack ready!${NC} Builds automatically when the dependency is first requested."
    echo -e "  JitPack status: ${CYAN}https://jitpack.io/#ProxymanApp/atlantis${NC}"
    echo ""
    echo -e "  Users can add the dependency:"
    echo -e "    ${BOLD}implementation(\"com.github.ProxymanApp:atlantis:v${VERSION}\")${NC}"
    echo ""
fi

echo ""
echo -e "${GREEN}${BOLD}All done.${NC}"
