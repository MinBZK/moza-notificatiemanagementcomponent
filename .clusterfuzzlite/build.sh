#!/bin/bash -eu

# Build the application and the test classes that hold the fuzz targets. test-compile is enough:
# the targets run against these classes, nothing here needs the packaged application.
./mvnw test-compile -Djacoco.skip=true -B

# Copy all dependencies to $OUT/lib. Test scope stays in on purpose: untransformed
# io.quarkus.logging.Log falls back to org.junit.jupiter.api.Assertions outside Quarkus,
# so without junit-jupiter-api every Log.* call in an error path throws.
mkdir -p $OUT/lib
./mvnw dependency:copy-dependencies -DoutputDirectory=$OUT/lib -B

# Copy compiled application and test classes
cp -r target/classes $OUT/classes
cp -r target/test-classes $OUT/test-classes

# Bundle the ENTIRE JDK 25 runtime to $OUT so the runner can execute Java 25 bytecode.
# Uses rsync -aL to dereference symlinks (critical for JDK directory structure).
# Pattern taken from the oss-fuzz tomcat project.
mkdir -p "$OUT/open-jdk-25"
rsync -aL --exclude='*.zip' "$JAVA_HOME/" "$OUT/open-jdk-25/"

# Create a wrapper script for every standalone fuzzer
# (classes that define the static fuzzerTestOneInput method expected by jazzer_driver)
for fuzzer in $(grep -rl "fuzzerTestOneInput" src/test/java/ || true); do
  class_name=$(echo "$fuzzer" | sed 's|src/test/java/||;s|\.java$||;s|/|.|g')
  simple_name=$(basename -s .java "$fuzzer")

  echo "Creating fuzzer wrapper: $simple_name -> $class_name"

  cat > "$OUT/$simple_name" << 'WRAPPER_EOF'
#!/bin/bash
# LLVMFuzzerTestOneInput for jvm
this_dir=$(dirname "$0")

if [[ "$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi

# Build classpath from compiled classes and all dependency jars
CP="$this_dir/test-classes:$this_dir/classes"
for jar in "$this_dir"/lib/*.jar; do
  CP="$CP:$jar"
done

# Set JAVA_HOME and LD_LIBRARY_PATH inline so jazzer_driver loads
# the bundled JDK 25 libjvm.so (not the runner's JDK 17).
# Pattern taken from the oss-fuzz tomcat project.
JAVA_HOME="$this_dir/open-jdk-25" \
LD_LIBRARY_PATH="$this_dir/open-jdk-25/lib/server":"$this_dir" \
"$this_dir/jazzer_driver" \
  --agent_path="$this_dir/jazzer_agent_deploy.jar" \
  --cp="$CP" \
  --target_class=TARGET_CLASS_PLACEHOLDER \
  --jvm_args="$mem_settings" \
  "$@"
WRAPPER_EOF

  sed -i "s|TARGET_CLASS_PLACEHOLDER|$class_name|" "$OUT/$simple_name"
  chmod +x "$OUT/$simple_name"
done

# Package seed corpora: the runner unpacks $OUT/<fuzzer>_seed_corpus.zip into the corpus
# before fuzzing, so short PR runs start from valid requests instead of empty input.
for corpus_dir in .clusterfuzzlite/seed-corpus/*/; do
  [ -d "$corpus_dir" ] || continue
  zip -j "$OUT/$(basename "$corpus_dir")_seed_corpus.zip" "$corpus_dir"*
done
