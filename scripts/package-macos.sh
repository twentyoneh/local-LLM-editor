#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"
if [[ "$(uname -s)" != Darwin ]]; then echo 'Эта сборка предназначена для macOS.' >&2; exit 1; fi
if [[ -z "${JAVA_HOME:-}" ]]; then export JAVA_HOME="$(/usr/libexec/java_home)"; fi
if [[ -z "${JAVA_HOME:-}" ]]; then echo 'Задайте JAVA_HOME для JDK 21+ с jpackage.' >&2; exit 1; fi
"$PROJECT_DIR/scripts/dev.sh" -q package
PACKAGE_OUT="$PROJECT_DIR/dist/LocalDoc-macos-$(uname -m)"
if [[ -e "$PACKAGE_OUT/LocalDoc.app" ]]; then echo "Сборка уже существует: $PACKAGE_OUT/LocalDoc.app. Переместите её перед повторной упаковкой." >&2; exit 1; fi
PACKAGE_INPUT="$(mktemp -d "$PROJECT_DIR/target/package-input.XXXXXX")"
trap 'rm -rf "$PACKAGE_INPUT"' EXIT
mkdir -p "$PACKAGE_INPUT/lib" "$PACKAGE_OUT/config" "$PACKAGE_OUT/actions"
cp target/local-llm-editor-1.0.0.jar "$PACKAGE_INPUT/"
cp target/lib/*.jar "$PACKAGE_INPUT/lib/"
cp config/config.properties "$PACKAGE_OUT/config/"
cp actions/*.json "$PACKAGE_OUT/actions/"
cp README.md "$PACKAGE_OUT/README.md"
"$JAVA_HOME/bin/jpackage" --type app-image --name LocalDoc --app-version 1.0.0 \
  --vendor LocalDoc --description 'Редактор документов с локальными LLM' \
  --input "$PACKAGE_INPUT" --dest "$PACKAGE_OUT" \
  --main-jar local-llm-editor-1.0.0.jar --main-class ru.localdoc.Launcher \
  --java-options '-Dlocaldoc.config=$APPDIR/../../../config/config.properties' \
  --java-options '-Dfile.encoding=UTF-8'
printf 'Готово: %s\n' "$PACKAGE_OUT/LocalDoc.app"
