# source ./env.sh — локальный тулчейн RouterSpot (JDK 17 + Android SDK без sudo)
export JAVA_HOME="$HOME/.local/jdk/jdk-17.0.21+9"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
