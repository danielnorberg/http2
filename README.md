# h2client

Maven
=====

```
    <dependency>
        <groupId>io.norberg</groupId>
        <artifactId>h2client</artifactId>
        <version>1.0-SNAPSHOT</version>
    </dependency>
```


OpenSSL
=======

To use OpenSSL, add appropriate netty-tcnative dependencies to the application project. Multiple dependencies can be
added to produce an artifact that can run unchanged on multiple platforms.

Note that OpenSSL >= 1.0.2 is required.

Available classifiers:

* windows-x86_64
* osx-x86_64
* linux-x86_64
* linux-x86_64-fedora

E.g.

```
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-tcnative-boringssl-static</artifactId>
      <version>1.1.33.Fork20</version>
      <classifier>osx-x86_64</classifier>
      <scope>test</scope>
    </dependency>
```

```
    <dependency>
      <groupId>io.netty</groupId>
      <artifactId>netty-tcnative-boringssl-static</artifactId>
      <version>1.1.33.Fork20</version>
      <classifier>linux-x86_64</classifier>
      <scope>test</scope>
    </dependency>
```

See more: http://netty.io/wiki/forked-tomcat-native.html

Benchmark
=========

```
mvn exec:exec -Dexec.executable="java" -Dexec.classpathScope="test" -Dexec.args="-cp %classpath -Dio.netty.leakDetection.level=disabled io.norberg.h2client.benchmarks.BenchmarkServer"
```

```
mvn exec:exec -Dexec.executable="java" -Dexec.classpathScope="test" -Dexec.args="-cp %classpath -Dio.netty.leakDetection.level=disabled io.norberg.h2client.benchmarks.BenchmarkClient"
```