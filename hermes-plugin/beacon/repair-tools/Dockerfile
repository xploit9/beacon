FROM python@sha256:90744cff8f32887f075c47d747a173ff333e9e98801667af93c357fa9f5e28ff

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle/gradle-8.14.6

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git openjdk-21-jdk-headless unzip \
    && rm -rf /var/lib/apt/lists/*

RUN python -m pip install --no-cache-dir \
      pytest==9.1.1 pytest-asyncio==1.3.0 httpx==0.28.1 \
      fastapi==0.133.1 uvicorn==0.41.0 pydantic==2.13.4 cryptography==50.0.1

RUN mkdir -p /opt/android-sdk/cmdline-tools /opt/gradle /opt/beacon-repair \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/android-tools.zip \
    && echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/android-tools.zip' | sha256sum -c - \
    && unzip -q /tmp/android-tools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/pinned \
    && rm /tmp/android-tools.zip \
    && curl -fsSL https://services.gradle.org/distributions/gradle-8.14.6-bin.zip -o /tmp/gradle.zip \
    && echo '7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24  /tmp/gradle.zip' | sha256sum -c - \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm /tmp/gradle.zip

COPY licenses/android-sdk-license /opt/android-sdk/licenses/android-sdk-license
RUN /opt/android-sdk/cmdline-tools/pinned/bin/sdkmanager --sdk_root=/opt/android-sdk \
      'platforms;android-36' 'build-tools;35.0.0' 'build-tools;36.0.0'

COPY pytest_harness.py pytest.ini gradle-init.gradle gradle_harness.py /opt/beacon-repair/
RUN chmod 0444 /opt/beacon-repair/pytest_harness.py /opt/beacon-repair/pytest.ini /opt/beacon-repair/gradle-init.gradle /opt/beacon-repair/gradle_harness.py
