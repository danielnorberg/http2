# http2

[![CircleCI](https://circleci.com/gh/danielnorberg/http2.svg?style=svg)](https://circleci.com/gh/danielnorberg/http2)
[![codecov](https://codecov.io/gh/danielnorberg/http2/branch/master/graph/badge.svg)](https://codecov.io/gh/danielnorberg/http2)

Http/2 client and server library with a focus on performance.

Maven
=====

```
    <dependency>
        <groupId>io.norberg</groupId>
        <artifactId>http2</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
```


BoringSSL / OpenSSL
===================

To use BoringSSL or OpenSSL, add appropriate netty-tcnative dependencies to the application project. Multiple
dependencies can be added to produce an artifact that can run unchanged on multiple platforms.

Note: OpenSSL >= 1.0.2 is required for ALPN support.

```
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-tcnative-boringssl-static</artifactId>
      <version>2.0.0.Final</version>
    </dependency>
```

See more: http://netty.io/wiki/forked-tomcat-native.html

Benchmark
=========

```
mvn exec:exec -Dexec.executable="java" -Dexec.classpathScope="test" -Dexec.args="-cp %classpath -Dio.netty.leakDetection.level=disabled io.norberg.http2.benchmarks.BenchmarkServer"
```

```
mvn exec:exec -Dexec.executable="java" -Dexec.classpathScope="test" -Dexec.args="-cp %classpath -Dio.netty.leakDetection.level=disabled io.norberg.http2.benchmarks.BenchmarkClient"
```
