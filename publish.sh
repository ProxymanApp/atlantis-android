#!/usr/bin/env bash
# publish.sh — Publish Atlantis Android to JitPack and/or Maven Central.
#
# Usage:
#   ./publish.sh --version 1.2.0                            # prepare a Maven Central bundle
#   ./publish.sh --target jitpack       --version 1.2.0
#   ./publish.sh --target maven-central  --version 1.2.0
#   ./publish.sh --target maven-central  --version 1.2.0 --skip-git
#   ./publish.sh --version 1.2.0 --dry-run
#
# Flags:
#   --target   jitpack | maven-central | both      (optional, defaults to maven-central)
#   --version  Semver string                   (required, e.g. 1.2.0 or 1.2.0-SNAPSHOT)
#   --skip-git Skip commit/tag/release actions     (optional)
#   --dry-run  Run local build checks only

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
            [[ -n "${2:-}" && "$2" != --* ]] || fail "--target requires a value."
            TARGET="$2"
            shift 2
            ;;
        --version)
            [[ -n "${2:-}" && "$2" != --* ]] || fail "--version requires a value."
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

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
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

if [[ "$SKIP_GIT" == false && "$DRY_RUN" == false && ( "$TARGET" == "jitpack" || "$TARGET" == "both" ) ]]; then
    command -v gh >/dev/null 2>&1 || fail "'gh' (GitHub CLI) not found. Install via: brew install gh"
    info "gh found: $(gh --version | head -1)"
fi

if [[ "$SKIP_GIT" == false ]]; then
    command -v git >/dev/null 2>&1 || fail "'git' not found."
    info "git found: $(git --version)"
    if [[ "$DRY_RUN" == false ]]; then
        RELEASE_BRANCH=$(git symbolic-ref --short HEAD) || fail "Start from a branch checkout or use --skip-git."
        [[ -z "$(git status --porcelain)" ]] || fail "Start from a clean checkout."
        if git show-ref --verify --quiet "refs/tags/$VERSION" || git show-ref --verify --quiet "refs/tags/v$VERSION"; then
            fail "A local tag for $VERSION already exists."
        fi
        REMOTE_TAGS=$(git ls-remote --tags origin "refs/tags/$VERSION" "refs/tags/v$VERSION") ||
            fail "Cannot check existing tags on origin."
        [[ -z "$REMOTE_TAGS" ]] || fail "A tag for $VERSION already exists on origin."
    fi
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
# Step 2: Select the build version
# ---------------------------------------------------------------------------
step "Selecting the build version"

# Read current VERSION_CODE and increment
CURRENT_CODE=$(grep '^VERSION_CODE=' "$GRADLE_PROPS" | cut -d'=' -f2)
if ! [[ "$CURRENT_CODE" =~ ^[0-9]+$ ]]; then
    fail "Invalid VERSION_CODE in $GRADLE_PROPS: '$CURRENT_CODE'"
fi
NEW_CODE=$((CURRENT_CODE + 1))

BUILD_VERSION="-PVERSION_NAME=$VERSION"

info "VERSION_NAME → ${VERSION}"
info "VERSION_CODE → ${NEW_CODE} (was ${CURRENT_CODE})"

# ---------------------------------------------------------------------------
# Step 3: Verify the build and Maven packaging
# ---------------------------------------------------------------------------
step "Testing and checking Maven packaging"

SMOKE_REPOSITORY=$(mktemp -d)
trap 'rm -rf -- "$SMOKE_REPOSITORY"' EXIT
./gradlew --no-daemon "$BUILD_VERSION" "-Dmaven.repo.local=$SMOKE_REPOSITORY" \
    :atlantis:test :atlantis:assembleRelease :atlantis:publishToMavenLocal

# ---------------------------------------------------------------------------
# Maven Central
# ---------------------------------------------------------------------------
if [[ "$TARGET" == "maven-central" || "$TARGET" == "both" ]]; then
    if [[ "$VERSION" == *-SNAPSHOT ]]; then
        step "Publishing snapshot to Maven Central"
        CENTRAL_TASK=:atlantis:publishReleasePublicationToSonatypeRepository
    else
        step "Building the signed Maven Central bundle"
        CENTRAL_TASK=:atlantis:bundleMavenCentralRelease
    fi
    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would run $CENTRAL_TASK"
    else
        ./gradlew --no-daemon "$BUILD_VERSION" "$CENTRAL_TASK"
    fi
fi

# --- Git: commit version bump, tag, push (shared, runs once) ---------------
if [[ "$SKIP_GIT" == false ]]; then
    step "Committing version bump"

    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would commit gradle.properties changes"
    else
        replace_line "VERSION_NAME" "VERSION_NAME=${VERSION}" "$GRADLE_PROPS"
        replace_line "VERSION_CODE" "VERSION_CODE=${NEW_CODE}" "$GRADLE_PROPS"
        git add "$GRADLE_PROPS"
        git commit -m "chore: bump version to ${VERSION}"
        info "Committed version bump"
    fi

    step "Creating Git tag $VERSION"

    if [[ "$DRY_RUN" == true ]]; then
        warn "[dry-run] Would create and push tag $VERSION"
    else
        git tag -a "$VERSION" -m "Release version $VERSION"
        git push --atomic origin "HEAD:refs/heads/$RELEASE_BRANCH" "refs/tags/$VERSION"
        info "Pushed $RELEASE_BRANCH and tag $VERSION to origin"
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
        warn "[dry-run] Would create GitHub release $VERSION"
    else
        gh release create "$VERSION" \
            --verify-tag \
            --title "$VERSION" \
            --generate-notes
        info "GitHub release $VERSION created"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""

if [[ "$DRY_RUN" == false && "$VERSION" != *-SNAPSHOT && ( "$TARGET" == "maven-central" || "$TARGET" == "both" ) ]]; then
    ARTIFACT_ID=$(grep '^POM_ARTIFACT_ID=' "$GRADLE_PROPS" | cut -d'=' -f2)
    info "Maven Central bundle: atlantis/build/distributions/${ARTIFACT_ID}-${VERSION}-central.zip"
    info "Upload the bundle in Central Portal, then publish it after validation. See PUBLISHING.md."
fi

if [[ "$DRY_RUN" == false && "$SKIP_GIT" == false && ( "$TARGET" == "jitpack" || "$TARGET" == "both" ) ]]; then
    echo -e "${GREEN}${BOLD}JitPack ready!${NC} Builds automatically when the dependency is first requested."
    echo -e "  JitPack status: ${CYAN}https://jitpack.io/#ProxymanApp/atlantis-android${NC}"
    echo ""
    echo -e "  Users can add the dependency:"
    echo -e "    ${BOLD}implementation(\"com.github.ProxymanApp:atlantis-android:${VERSION}\")${NC}"
    echo ""
fi

echo ""
echo -e "${GREEN}${BOLD}All done.${NC}"
