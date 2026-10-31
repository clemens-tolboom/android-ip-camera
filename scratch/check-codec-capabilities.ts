/** Run offline JVM regression tests and compile debug APKs; never connects to a device. */
const child = Bun.spawn([
  "./gradlew", "--offline", "--no-daemon", "testDebugUnitTest", "assembleDebug",
  "-PskipSigning", "--console=plain",
], { cwd: new URL("..", import.meta.url).pathname, stdout: "inherit", stderr: "inherit" });
process.exit(await child.exited);
