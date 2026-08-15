# 在 Docker 里调试 scip-java(保姆级图文说明)

> 目标读者:想在本地(中国大陆网络环境)修改 scip-java 代码、并且想在**自己改动的代码**上打断点调试的开发者。本文假设你已经能读懂 scip-java 大致是干什么的。

---

## 1. 先理解三件事,后面就不晕了

### 1.1 scip-java 的代码到底"跑"在哪里?

scip-java 是一个 **javac 编译插件**。它的"主程序"不是自己独立的进程,而是**挂在你项目的编译过程里**。

你在命令行敲 `javac xxx.java` 时,javac 会经历几个阶段(解析 → 语义分析 → 生成字节码)。scip-java 通过 `-Xplugin:scip` 把自己注册进 javac,在**语义分析完毕**(javac 内部叫 `ANALYZE`)时被 javac 回调,然后遍历语法树、生成 SCIP 结果。

所以关键认知是:

> **要让 scip-java 的代码跑起来,必须先有一次真实的 `javac` 编译。没有编译,插件代码一行也不会执行。**

这意味着调试 scip-java,本质就是:**让一个挂着 scip 插件、并且带调试器接口的 javac 进程跑起来,然后在它执行到插件代码时停下。**

### 1.2 为什么我们非要折腾 Docker?

1. **编译环境一致性**:编译插件最怕和"真实编译环境"不一样。Docker 镜像里预装了 Gradle/Maven/多个 JDK,能复现 CI 环境。
2. **国内网络问题**:镜像里下载依赖走的是被墙的源,需要按同仓库的 `china-network-guide.md` 用国内镜像。
3. **干净可重来**:调试环境坏了随时 `docker rm -f`,不影响宿主机。

### 1.3 几个必须记的名词

| 名词 | 是什么 |
|---|---|
| `javac` | Java 编译器。插件跑在它内部。 |
| `jdkp / jdwp` | Java Debug Wire Protocol,**让 JVM 能被外部调试器连接的协议**。启动 javac 时加 `-agentlib:jdwp=...` 就能开启。 |
| `jdb` | JDK 自带的最小调试器,命令行交互,依附于 jdwp。不需要装任何 IDE。 |
| `suspend=y` | 开启 jdwp 后,JVM **一启动就立刻挂起**,停在第一行代码之前,等你 attach。 |
| `suspend=n` | JVM 启动后直接跑,不等调试器(不利于设断点,本文不用)。 |
| shadow jar / 重定位 | 项目用 Gradle Shadow 插件把依赖打成一个"胖 jar",同时把内部类包名加前缀(`org.scip_code.scip_java` → `org.scip_code.scip_java.shaded.org.scip_code.scip_java`)。**这导致你打断点用的类名要带 `shaded` 前缀,后面第 6 节专门讲。** |

---

## 2. 全景图:调试是怎么一步步发生的

```
+-------------------宿主机------------------+        +-----------容器(ghcr.io/scip-code/scip-java)----------+
|                                            |  5005  |                                                     |
|  jdb -attach localhost:5005   <-------- jdwp 端口--->   javac 进程                                        |
|  (调试器,JDK自带)                        | reach  |     + 挂载了 scip-plugin.jar(你刚 build 的)          |
|                                            |        |     + 挂载了样例源码 /sources                        |
|                                            |        |     suspend=y:javac 一启动就停住,等调试器             |
+--------------------------------------------+        +-----------------------------------------------------+
```

顺序如下:

1. 你在容器里敲 `javac -J-agentlib:jdwp=...,suspend=y ...` → javac 启动,**立刻挂起**,端口 5005 等待调试器。
2. 你在宿主机敲 `jdb -attach localhost:5005` → 连上(此时 javac 还暂停着)。
3. 先用 `stop in 全限类名.方法` 设好断点。
4. 敲 `resume` 让 javac 继续跑编译。
5. javac 编译到某个文件的 `ANALYZE` 阶段,插件代码被执行,**执行到你设的断点处自动停下**。
6. 你就能看调用栈(`where`)、看局部变量(`locals`)、单步(`next`/`step`)、改值、继续(`cont`)……如同 IDE 里一样。

---

## 3. 一次性准备(已经帮你做好了,说明一遍)

### 3.1 拉取官方镜像(走南大代理)

`ghcr.io` 在大陆连不上。按 `china-network-guide.md`,用南京大学代理拉,再改回原名:

```bash
docker pull ghcr.nju.edu.cn/scip-code/scip-java:latest
docker tag ghcr.nju.edu.cn/scip-code/scip-java:latest ghcr.io/scip-code/scip-java:latest
```

> 为什么先拉官方镜像而不是自己 build?官方镜像里**预装了 Gradle 9.4.1、Maven、coursier 和 JDK 25/21/17**,我们后面构建/调试全依赖它们。它内置的 scip 插件是旧发布版,但**我们不会用它**,只用它当"工具环境"。

### 3.2 现有文件清单

| 位置 | 作用 |
|---|---|
| `/Users/ydd/github/scip-sample/debug-javac.sh` | **容器内脚本**:选 JDK → 定位插件 jar → 以 `suspend=y` 挂起方式跑 javac |
| `/Users/ydd/github/scip-sample/debug-kotlinc.sh` | **容器内脚本**:同上,但跑 kotlinc 2.4.10(进程内 K2JVMCompiler)调 Kotlin 插件(第 9 节) |
| `/Users/ydd/github/scip-sample/src/main/java/example/*.java` | 样例 Java 工程(`JavaBasics`,`JavaOop`,`JavaAnnotations`,`ExternalRefs`):基础语法/OOP/泛型/record/枚举/sealed/switch/注解等,含跨文件引用与外部依赖引用 |
| `/Users/ydd/github/scip-sample/src/main/kotlin/example/*.kt` | 样例 Kotlin 工程(`KotlinClasses`,`KotlinFunctions`,`KotlinGenerics`,`ExternalRefs`):类体系/属性/扩展/操作符/suspend/泛型/作用域函数等,含跨文件引用与外部依赖引用 |
| `/Users/ydd/github/scip-sample/{settings,build}.gradle.kts` | **Gradle 工程定义**(java + kotlin 2.4.10),声明 Guava / kotlinx-serialization 坐标;`copyDeps` 任务把编译 classpath 导出到 `build/deps/` |
| `/Users/ydd/github/scip-sample/build/deps/classpath.txt` | `gradle copyDeps` 生成的相对路径 classpath(`build/deps/xxx.jar:...`),`debug-javac.sh`/`debug-kotlinc.sh` 读取作为 `-classpath` |
| `/Users/ydd/.cache/scip-kotlinc24/` | kotlinc 2.4.10 的 4 个 jar(compiler-embeddable/stdlib/reflect/script-runtime),从主机 Gradle 缓存拷出,挂到容器 `/opt/kotlinc` |
| `/Users/ydd/github/scip-java/scripts/dbg-attach.sh` | **宿主脚本**:自动 `jdb attach` javac 调试容器 → 设断点 → `resume` → 命中后打印调用栈和局部变量 |
| `/Users/ydd/github/scip-java/scripts/dbg-kotlin-attach.sh` | **宿主脚本**:同上,attach Kotlin 调试容器(端口 5006,第 9.6 节) |
| `/Users/ydd/github/scip-java/scripts/scip-repos.gradle` | **Gradle 国内镜像重定向脚本**(第 4 节用) |

---

## 4. 构建"你当前代码"的插件 jar(方案 B 核心)

### 4.1 为什么必须自己构建

官方镜像里的插件是**发布版旧代码**(它的 `ScipVisitor` 只有 3 个方法)。你改了仓库代码后,这个东西完全不知道你的改动。所以要**用仓库源码重新构建插件 jar**,让 javac 加载它。

### 4.2 构建原理

- 官方镜像里自带 Gradle 9.4.1,所以不用在宿主机装任何工具。
- 国内网络下,Gradle 访问的 `plugins.gradle.org` / `repo.maven.apache.org` 是不稳的,所以要用 `china-network-guide.md` 第 2 节那个 init 脚本把仓库地址**重定向到阿里云/腾讯镜像**。
- 我们是**挂载仓库目录**进容器(`-v /Users/ydd/github/scip-java:/repo`),容器里 `cd /repo && gradle ...` 编译的**就是你的工作区代码**。

### 4.3 步骤

```bash
# (1) 启动"构建容器"
docker rm -f scip-build                       # 清掉旧容器(没有也无所谓)
docker run -d --name scip-build \
  -v /Users/ydd/github/scip-java:/repo \                            # 你的仓库挂载进容器
  -v /Users/ydd/github/scip-java/scripts/scip-repos.gradle:/root/.gradle/init.d/scip-repos.gradle \  # 镜像 init 脚本
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'cd /repo && gradle --no-daemon --console=plain :scip-javac:shadowJar'

# (2) 跟日志,等 BUILD SUCCESSFUL(首次约 5 分钟)
docker logs -f scip-build
```

**产物位置**(重要):

```
/Users/ydd/github/scip-java/scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all.jar
```

> 为什么在 `target/gradle` 而不是 `build`?仓库里 `scip.project-base` 约定:带 Bazel `BUILD` 文件的模块把 Gradle 输出放到 `target/gradle`,避免和 Bazel 目录冲突。构建产物已 gitignore,不会弄脏仓库。

**每次改了代码怎么办?** 把第 4.3 节的(1)(2)再跑一遍即可(增量编译会快很多)。然后按第 5 节重启调试容器。

### 4.4 验证构建的是不是当前代码

```bash
javap -p -classpath /Users/ydd/github/scip-java/scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all.jar \
  org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor
```

若看到 `resolveMethodTree`/`emitOccurrence`/`emitSymbolInformation` 等当前仓库才有的私有方法,说明 jar 是对的最新代码(旧发布版的 `ScipVisitor` 没有这些)。

### 4.5 加外部依赖(样例已是 Gradle 工程)

样例现在是**真正的 Gradle 工程**(java + kotlin 2.4.10),依赖用坐标声明,再让 Gradle 解析出 classpath 喂给调试启动器。这样不用改启动器、不用手工找 jar。

```bash
# ① 改依赖:编辑 /Users/ydd/github/scip-sample/build.gradle.kts 的 dependencies {} 加坐标

# ② 重新解析 + 导出 classpath(国内镜像,建议在常驻容器里跑,首次约 1 分钟)
docker run --rm --name scip-sample-build \
  -v /Users/ydd/github/scip-sample:/workspace \
  -v /Users/ydd/github/scip-java/scripts/scip-repos.gradle:/root/.gradle/init.d/scip-repos.gradle \
  --workdir /workspace \
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'gradle --no-daemon --console=plain build copyDeps'

# ③ 产物:build/deps/*.jar + build/deps/classpath.txt(相对路径,调试容器以 /sources 为 CWD 直接用)

# ④ 重启两个调试容器(见 5.1 / 9.4),启动器会自动读 classpath.txt 作为 -classpath
```

> 调试容器里 `debug-javac.sh` / `debug-kotlinc.sh` 会检测 `/sources/build/deps/classpath.txt`,存在就用它。
> 想验证外部符号解析,断点打在 javac `resolveMethodTree`(外部类的 `Element` 也能拿到)、Kotlin `visitSimpleNameExpression`(看 `resolvedSymbol` 指向 `kotlinx.serialization` 等)。

---

### 4.6 查看产物:聚合 shard + 导出 JSON

调试跑完后,shard 落在 `/sources/META-INF/scip/`,但 shard 是**每个源文件一个二进制 Index**,还没合并、肉眼也看不了。用以下两步把它聚合成 `index.scip` 并导出 JSON(调试容器已挂载重建过的 CLI):

```bash
# 在任意调试容器里(/sources 已挂载样例):
docker exec scip-debug bash -lc 'cd /sources && \
  scip-java aggregate --targetroot META-INF/scip --output index.scip && \
  scip-java dump-json index.scip > index.json'
```

- `aggregate`:把 `META-INF/scip` 下所有 shard 合并成一个 `index.scip`(binary proto)。
- `dump-json`:读任意 `.scip`(合并的 index 或单个 shard),用 protojson 打成 JSON 输出到 stdout。
  - JSON 里每个 occurrence 带 `singleLineRange`(行号/列号,不用字节偏移),好对照源码;
  - 符号是展开形式(`scip-java maven . . com/google/common/base/Joiner#on().B`),外部依赖引用会以 `scip-java maven . . <外部包路径>...` 出现。
  - **`externalSymbols`(Index 顶层)是"被引用但本代码库未定义"的符号表**。两个编译插件在遇到非 local、非包路径的全局符号时记录候选;聚合器再减去文档实际定义的符号,剩下的写入 `externalSymbols`。因此它带 kind/displayName/signatureDocumentation,可直接用于生成自己的语义产物:
    ```json
    {"symbol":"scip-java maven . . com/google/common/base/Joiner#on().",
     "kind":"StaticMethod","displayName":"on",
     "signatureDocumentation":{"language":"java","text":"public static Joiner on(String arg0)"}}
    ```
- 注意:容器里 `/usr/bin/scip-java` wrapper 的 `Using JVM version` 提示已改为打到 **stderr**,`dump-json` 的 stdout 是干净 JSON,可 `>` 重定向到文件。

> 两个 shard 需要分别调试时,Java 和 Kotlin 各调一次,产物分开(都在 `META-INF/scip` 下,`relativePath` 区分 `src/main/java` 与 `src/main/kotlin`)。

---

## 5. 调试流程(每一步都有解释)

### 5.1 启动"等待调试器"的容器

```bash
docker rm -f scip-debug

docker run -d --name scip-debug \
  -p 5005:5005 \
  -v /Users/ydd/github/scip-sample:/sources \
  -v /Users/ydd/github/scip-java/scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all-debug.jar:/opt/plugin/scip-plugin.jar \
  -v /Users/ydd/github/scip-java/scip-java/build/install/scip-java:/app/scip-java \
  -v /Users/ydd/github/scip-java/bin/scip-java-docker-script.sh:/usr/bin/scip-java \
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'cd /sources && bash debug-javac.sh 5005 21 src/main/java/example/*.java'
```

逐项解释:

| 参数 | 含义 |
|---|---|
| `-p 5005:5005` | 把容器内 jdwp 端口映射到宿主机,宿主机才能 `localhost:5005` 连上 |
| `-v /Users/ydd/github/scip-sample:/sources` | 样例代码挂进容器;javac 在容器里编译它,插件才有活干 |
| `-v ...all-debug.jar:/opt/plugin/scip-plugin.jar` | **关键**:把第 4 节刚构建的、基于你当前代码的插件 jar 塞进容器。`debug-javac.sh` 会优先用它(`-processorpath`) |
| `-v .../scip-java/build/install/scip-java:/app/scip-java` | 挂载**重建后的 CLI**(带 `dump-json` 子命令),否则容器用的是镜像自带的旧版 CLI,无法导出 JSON |
| `-v .../bin/scip-java-docker-script.sh:/usr/bin/scip-java` | 挂载修好的 wrapper(JVM 提示打到 stderr),保证 `dump-json` stdout 是纯 JSON |
| `bash debug-javac.sh 5005 21 源文件...` | `5005`=调试端口,`21`=用 JDK21(镜像内置),后面传给 javac 的源文件 |

`debug-javac.sh` 内部其实做了三件事:

1. 用 `coursier` 切换到指定 JDK;
2. 定位插件 jar(有挂载的用挂载的,否则解包镜像内置的);
3. 执行:
   ```bash
   javac \
     -J-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005 \
     -J$(cat /app/scip-java/javac-jvm-options) \
     -processorpath /tmp/scip-plugin/scip-plugin.jar \
     "-Xplugin:scip -sourceroot:/sources -targetroot:/sources" \
     src/main/java/example/*.java
   ```

要点逐一说明:

- `-J...`:javac 启动的是它自己的 JVM,`-J` 把参数传给这个 JVM。
- `-agentlib:jdwp=...,suspend=y,address=*:5005`:`suspend=y` = 一启动就挂起,等调试器;`address=*:5005` = 在容器所有网卡上监听,配合 `-p 5005` 才能被宿主机访问。
- `-J$(cat /app/scip-java/javac-jvm-options)`:插件要用 javac 内部包,JDK17+ 默认不开放,必须带那 5 个 `--add-exports`。镜像把现成的参数固化在 `/app/scip-java/javac-jvm-options` 文件里,直接读进来。
- `-processorpath ...scip-plugin.jar`:让 javac 能找到插件。
- **`"-Xplugin:scip -sourceroot:/sources -targetroot:/sources"` 必须是一个整体参数**(用引号包住)。javac 规定 `-Xplugin` 的插件名和参数同属一个 argv;拆成两个参数,会被 javac 当成"未知编译选项"而报 `invalid flag`。

启动后确认它在等待:

```bash
docker logs scip-debug 2>&1 | grep Listening
# 期望:Listening for transport dt_socket at address: 5005
```

> 注意:此时 javac **正挂起着,并不会真的开始编译**。你不 attach,它就永远等。

### 5.2 attach 并打断点(脚本一键)

```bash
bash /Users/ydd/github/scip-java/scripts/dbg-attach.sh \
  org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor.resolveMethodTree
```

脚本替你执行了:attach → `stop in <断点>` → `resume` → 等命中 → 打印 `where`/`locals` → `quit`。

期望输出(节选):

```
断点命中: "线程=main", ...ScipVisitor.resolveMethodTree(), 行=174 bci=0
[1] ...ScipVisitor.resolveMethodTree (ScipVisitor.java:174)   ← 插件在遍历 AST 时处理一个方法定义
[2] ...ScipVisitor.resolveNodes (ScipVisitor.java:144)
[3] ...ScipVisitor.visitCompilationUnit (ScipVisitor.java:97)
[4] ...ScipTaskListener.onFinishedAnalyze (ScipTaskListener.java:131)  ← ANALYZE 事件回调
[5] ...ScipTaskListener.finished (ScipTaskListener.java:87)
[6] com.sun.tools.javac.api.ClientCodeWrapper$WrappedTaskListener.finished ...
[8] com.sun.tools.javac.main.JavaCompiler.flow (JavaCompiler.java:1436)  ← javac 编译管线
[14] com.sun.tools.javac.Main.main (Main.java:50)
方法参数:
node = instance of com.sun.tools.javac.tree.JCTree$JCMethodDecl(...)
treePath = instance of com.sun.source.util.TreePath(...)
```

这个栈告诉你:**javac 自己编译 → 触发 ANALYZE → scip 插件收到回调 → 插件逐方法处理,走到了 `resolveMethodTree`**。行号与你本地 `ScipVisitor.java` 一致,证明跑的就是当前代码。

### 5.3 常用断点加分表

| 想看的阶段 | 断点 FQN |
|---|---|
| 插件注册入口(最早) | `org.scip_code.scip_java.javac.ScipPlugin.init`(不被重定位) |
| javac 每次编译事件 | `...javac.ScipTaskListener.finished` |
| 处理一个 MethodTree 定义 | `...javac.ScipVisitor.resolveMethodTree` |
| 发任何 occurrence(定义/引用) | `...javac.ScipVisitor.emitOccurrence` |
| 生成 SymbolInformation | `...javac.ScipVisitor.emitSymbolInformation` |
| 范围计算 | `...javac.ScipVisitor.computeRange` |

> 能打断点的方法 = 用 `javap -p` 能看到的方法(公有/私有都行)。断点所在类一定带 `shaded` 前缀,只有 `ScipPlugin`/`InjectScipOptions` 是例外(见第 6 节)。

### 5.4 手动 jdb 交互(学会它,就不怕脚本局限了)

不用脚本,直接:

```bash
jdb -attach localhost:5005
```

进入 `main[1]` 提示符后,按顺序:

```
# ① 设断点(类还没加载,会提示"延迟断点,将在加载类后设置",正常)
stop in org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor.resolveMethodTree

# ② 放行编译(重要:此时用 resume,不要用 cont)
#    因为启动即挂起状态下 jdb 没有"当前线程",cont 会报"未指定线程"
resume

# ③ javac 编译到 ANALYZE,断点命中
# ④ 命中后才是自由交互时间:
where         # 调用栈
next          # 执行当前源文件下一行
step          # 单步进入下一层
locals        # 当前方法局部变量
print 变量名    # 打印变量/字段,如 print sym
list          # 显示断点附近源码(需要配合 -sourcepath,见下)
cont          # 继续,直到下一个断点
clear <断点>   # 删除断点
exit          # 退出

# 如果想 list 显示源码,attach 时加 -sourcepath:
#   jdb -sourcepath /Users/ydd/github/scip-java/scip-javac/src/main/java -attach localhost:5005
```

常见交互套路:断点停在 `resolveMethodTree` 后,`next` 几次走到 `trees.getElement(treePath)` 附近,`locals` 看 `node`/`sym`,再 `cont` 看下一个方法。

### 5.5 修改样例、重跑调试

```bash
# 改 /Users/ydd/github/scip-sample/src/main/java/example/xxx.java
docker rm -f scip-debug
# 重新执行 5.1 的 docker run ...
```

每次调试都是"起容器(挂起) → attach(打断点) → resume"这个循环。

---

## 6. 关键概念:断点类名 & shadow 重定位

这是最容易踩的坑,单独讲。

### 6.1 为什么类名带 `shaded`

项目用 Shadow 插件打 fat jar,并把 `org.scip_code.scip_java` 整个包名改写成 `org.scip_code.scip_java.shaded.org.scip_code.scip_java`(**除了** `ScipPlugin` 和 `InjectScipOptions` 两个类)。

所以:
- 打断点用:**`org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor.resolveMethodTree`**
- javac 实际加载的类,`javap` 看的类,都是这个带 `shaded` 的名字。

### 6.2 如何确定一个类的正确全名

```bash
# 列出 jar 内所有类(去掉 .class 尾巴就是 FQN)
jar tf <jar路径> | grep -i "visit" 

# 查看某个类有哪些方法(私有方法要 -p)
javap -p -classpath <jar路径> <全限类名>
```

### 6.3 三个"断点打不上"的典型原因

1. **类名没带 `shaded`**:javac 里根本没有 `org.scip_code.scip_java.javac.ScipVisitor` 这个类,断点永远挂"延迟"而且不激活。
2. **方法名有重载**:比如 `scip` 的重写方法 `scan(Tree, Void)` 和基类还有 `scan(Tree, Object)` 桥接方法,jdb 因歧义把断点挂起不激活。**选独有方法名**(如 `visitCompilationUnit`、`resolveMethodTree`)。
3. **断点没设上就 resume**:或者断点设在"插件运行之后才加载的类"上且从未命中——先确认 `docker logs scip-debug | grep Listening`,再确认 attach 成功(jdb 打出 `VM 已启动`)。

---

## 7. 常见问题(FAQ)

**Q1:attach 显示 `Connection refused`**
容器没在等。检查:`docker ps` 是否运行、`docker logs ... | grep Listening`。大概率容器已退出(编译已结束)。

**Q2:javac 报 `error: invalid flag: 5005`**
`debug-javac.sh` 被调用的方式不对,`5005`/`21` 混到源文件参数里了。正确:`bash debug-javac.sh 5005 21 src/....java`,且脚本内部有 `shift` 把前两个参数消费掉。

**Q3:`resume` 后根本没有"断点命中"**
按 6.3 检查类名/重载;或 confirm attach 前 javac 还没挂起完。

**Q4:`cont` 报 `未指定线程`**
启动即挂起的 JVM 没有默认线程,请用 `resume`。断点命中后才有"当前线程",之后 `cont`/`next`/`step` 才可用。

**Q5:想调试 Kotlin 插件?**
思路完全相同,只是插件 jar 换成 `:scip-kotlinc` 构建产物、`-Xplugin` 参数是 `-Xplugin:scip-kotlinc`,`-P plugin:scip-kotlinc:sourceroot=...`。断点类在 `org.scip_code.scip_java.shaded.` 下对应 kotlinc 包。

**Q6:构建容器日志出现镜像重定向提示?**
`scip-mirror: https://plugins.gradle.org/m2 -> https://maven.aliyun.com/...` 是**正常且需要的**,说明 init 脚本生效。没出现说明 `-v .../scip-repos.gradle` 没挂好。

**Q7:构建失败在下载依赖**
网络问题,确认 init 脚本挂载、镜像可达(`curl -I https://maven.aliyun.com/repository/google/`)后重试。`--no-daemon` 已避免 daemon 残留。

---

## 8. 用 IDE(IntelliJ IDEA)调试

> 上面的 jdb 是"能跑";IDE 才是"好用":可视化断点、变量面板、悬停看值、表达式求值、单步。本仓库已内置一个专门给 IDE 用的调试 jar 任务,详见下文。这里以 **IntelliJ IDEA Community(免费)** 为例。

### 8.1 为什么必须用"不重定位的调试 jar"

- 正式插件 jar(`...-all.jar`)用 Shadow 把类名改写成 `org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor`。
- IDEA 的断点是**按源码文件**设的(点击 `ScipVisitor.java` 的行号),而 JVM 加载的类叫 `shaded...ScipVisitor`,**包名对不上 → 断点永远不命中**。
- 解决办法:本项目 `scip-javac/build.gradle.kts` 里新增了 `shadowJarDebug` 任务,产出**不做重定位**的胖 jar:

  ```
  scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all-debug.jar
  ```

  这个 jar 里 `org.scip_code.scip_java.javac.ScipVisitor` 和源码完全同名同包,IDE 断点直接命中,源码行号、悬停、求值全部正常。

### 8.2 一次准备

1. 安装 **IntelliJ IDEA Community**,用 `File → Open` 打开 `/Users/ydd/github/scip-java`,信任并作为 Gradle 项目导入,等 Gradle sync 完成。
2. **把国内镜像 init 脚本放到宿主机**(否则 IDEA 的 Gradle sync 也会卡在被墙的仓库):
   ```bash
   mkdir -p ~/.gradle/init.d && cp /Users/ydd/github/scip-java/scripts/scip-repos.gradle ~/.gradle/init.d/
   ```
3. `Settings → Build Tools → Gradle → Gradle JVM` 选项目 JDK 17+ 或 IDEA 自带 JBR。

### 8.3 建远程调试配置

`Run → Edit Configurations… → + → Remote JVM Debug`:

| 项 | 值 |
|---|---|
| Name | `scip-docker` |
| Host | `localhost` |
| Port | `5005` |
| Module classpath | `scip-javac` |
| Search sources using module's classpath | 勾选 |

### 8.4 起调试容器(挂载 debug jar)

```bash
docker rm -f scip-debug
docker run -d --name scip-debug -p 5005:5005 \
  -v /Users/ydd/github/scip-sample:/sources \
  -v /Users/ydd/github/scip-java/scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all-debug.jar:/opt/plugin/scip-plugin.jar \
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'cd /sources && bash debug-javac.sh 5005 21 src/main/java/example/*.java'

docker logs scip-debug 2>&1 | grep Listening   # 期望 Listening ... 5005
```

### 8.5 打断点、attach、运行

1. 打开 `ScipVisitor.java`,在 `resolveMethodTree`(约 174 行)左侧点出红断点。
2. 点 `scip-docker` 配置的绿色 bug 图标。控制台出现 `Connected to the target VM...`。
3. **程序因 `suspend=y` 停在最开头,按 F9(Resume Program)放行。**
4. javac 跑到 `ANALYZE`,插件类加载,断点命中(红点变蓝):
   - **栈面板**:`resolveMethodTree → resolveNodes → visitCompilationUnit → ScipTaskListener.onFinishedAnalyze → finished → JavaCompiler.flow`。
   - **变量面板**:`node`、`treePath`、`this` 各字段均可展开。
   - **悬停/求值**:鼠标悬停看值;`Alt+F8` 输入 `sym.getSimpleName()` 等。
   - **单步**:`F8` 下一行、`F7` 进入、`Shift+F8` 跳出;`F9` 到下一断点。

> 每个顶层类型触发一次 `ANALYZE`(样例里 Hello、Calculator),断点会命中多次。

### 8.6 改了代码再调试

```bash
# ① 重新构建 debug jar(增量很快)
#    docker run -d --name scip-build ... gradle :scip-javac:shadowJarDebug
# ② 重启调试容器(8.4),IDEA 里重新点 Debug 即可
```

### 8.7 常见 IDE 问题

| 现象 | 原因/解法 |
|---|---|
| 断点灰掉/不命中 | 确认挂载 `-all-debug.jar`;确认 IDEA 已连上并 F9 放行过 |
| attach 连接失败 | 容器没 `Listening`,或没加 `-p 5005:5005` |
| 行号/源码对不上 | 改了代码没重新构建 jar,或 IDEA 没重新同步 |
| 想调试 Kotlin 插件 | 见第 9 节 |

---

## 9. 调试 Kotlin(scip-kotlinc 编译器插件)

> Java 用 javac 插件(scip-javac),Kotlin 用**另一个** Kotlin 编译器插件(scip-kotlinc)。
> 两者跑在**不同的 JVM 进程**,所以混用 Java+Kotlin 的项目要**分开两遍调试**,一次 attach 只能调一种语言。
> 不调试直接跑时 `scip-java index` 会自动先 javac 再 kotlinc、把两类 shard 合并成一个 `index.scip`,无需你操心。

### 9.1 架构差异(决定了调试方式不同)

| | Java(scip-javac) | Kotlin(scip-kotlinc) |
|---|---|---|
| 编译器 | `javac`(fork 独立进程) | `K2JVMCompiler`(**进程内**,`ScipBuildTool.kt:204`) |
| 插件加载 | `-Xplugin:scip` | `-Xplugin=<jar>`,`META-INF/services` 自动注册 `AnalyzerRegistrar` |
| 语义分析 | javac `TreeScanner` | **FIR**(`AnalyzerFirExtensionRegistrar` + `AnalyzerCheckers`) |
| 断点看什么 | `Element sym = trees.getElement(...)` | `firFunction`/`firProperty` 等 `Fir*Impl`,以及 `CheckerContext` |
| 调试端口 | 5005 | 5006 |

Kotlin 的语义分析结果直接暴露在断点变量里:`ScipVisitor.visitNamedFunction` 的 `firFunction` 是 `FirNamedFunctionImpl`(名字、返回类型、body、修饰符都在里面),`AnalyzerCheckers.kt:287` 的检查器还能看到调用上下文。

### 9.2 一次性准备(已帮你做好)

- `scip-kotlinc/build.gradle.kts` 新增 `shadowJarDebug` 任务 → 产出
  `/Users/ydd/github/scip-java/scip-kotlinc/build/libs/scip-kotlinc-0.0.0-SNAPSHOT-all-debug.jar`(不重定位)。
- kotlinc 2.4.10 运行环境拷到 `/Users/ydd/.cache/scip-kotlinc24/`(从主机 Gradle 缓存,
  与仓库 `gradle/libs.versions.toml` 的 `kotlin = "2.4.10"` 一致;**不能用镜像内置的 2.2.0**,FIR API 会不兼容)。
- 样例 `/Users/ydd/github/scip-sample/src/main/kotlin/example/*.kt`、启动器 `debug-kotlinc.sh`。

### 9.3 构建当前代码的 Kotlin 插件(改了 scip-kotlinc 源码时)

```bash
# 常驻"构建容器"里跑(容器已挂 /repo = 你的仓库 + 国内镜像 init 脚本)
docker exec scip-build bash -lc \
  'cd /repo && gradle --no-daemon --console=plain :scip-kotlinc:shadowJarDebug'
# 产物:scip-kotlinc/build/libs/scip-kotlinc-0.0.0-SNAPSHOT-all-debug.jar
```

### 9.4 起 Kotlin 调试容器

```bash
docker rm -f scip-debug-kotlin
docker run -d --name scip-debug-kotlin -p 5006:5006 \
  -v /Users/ydd/github/scip-sample:/sources \
  -v /Users/ydd/github/scip-java/scip-kotlinc/build/libs/scip-kotlinc-0.0.0-SNAPSHOT-all-debug.jar:/opt/plugin/scip-kotlin-plugin.jar \
  -v /Users/ydd/.cache/scip-kotlinc24:/opt/kotlinc \
  -v /Users/ydd/github/scip-java/scip-java/build/install/scip-java:/app/scip-java \
  -v /Users/ydd/github/scip-java/bin/scip-java-docker-script.sh:/usr/bin/scip-java \
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'cd /sources && bash debug-kotlinc.sh 5006 21 src/main/kotlin/example/*.kt'

docker logs scip-debug-kotlin 2>&1 | grep Listening   # 期望 Listening ... 5006
```

### 9.5 IDEA attach

1. 新建第二个 Remote JVM Debug 配置(端口 **5006**),Module classpath 选 `scip-kotlinc`(JVM/JDK 都行)。
2. 在 `ScipVisitor.kt` 里打断点,推荐:
   - `visitNamedFunction`(约 106 行)—— 函数定义,FIR 语义信息最丰富
   - `visitProperty`(约 117 行)—— 属性/字段
   - `visitSimpleNameExpression`(约 183 行)—— **引用**(这里能看 `resolvedSymbol` 解析到了谁)
   - `AnalyzerCheckers.kt`(约 281-287 行)—— FIR 检查器入口
3. 点调试绿色 bug → `Connected to the target VM...` → **F9 放行**(`suspend=y`)。
4. 断点命中后看变量面板:`firFunction`(FirNamedFunctionImpl,含 name/returnTypeRef/body)、
   `source`、`CheckerContext`;Alt+F8 可求值如 `firFunction.name.asString()`。

> 一个顶层函数命中一次 `visitNamedFunction`,`main`/`greet` 各一次。

### 9.6 脚本版验证(不依赖 IDEA)

```bash
# 宿主侧 jdb 驱动(断点默认 visitNamedFunction,可传参覆盖)
bash /Users/ydd/github/scip-java/scripts/dbg-kotlin-attach.sh \
  org.scip_code.scip_java.kotlinc.ScipVisitor.visitProperty
```

### 9.7 踩过的坑

| 现象 | 原因/解法 |
|---|---|
| `ClassNotFoundException: kotlinx.coroutines.CoroutineScope` | kotlin-compiler-embeddable 运行期需要 kotlinx;已在 `debug-kotlinc.sh` 的 `KOTLINC_CP` 里补上镜像 lib 的 kotlinx/serialization/annotations jar |
| `cannot access built-in declaration 'kotlin.Int'` | 编译期 classpath 缺 stdlib,`debug-kotlinc.sh` 已加 `-classpath /opt/kotlinc/kotlin-stdlib-2.4.10.jar` |
| `ClassNotFoundException: org.jetbrains.annotations.NotNull` | 后端 codegen 需要 org.jetbrains:annotations,已补 `annotations-13.0.jar` |
| Kotlin 版本不兼容 | 仓库是 2.4.10,镜像内置 2.2.0;必须用 `/opt/kotlinc` 里 2.4.10 的 jar 起编译器 |
| jdb `quit` 后容器 exit≠0 | 正常:`quit` 会终止被调试 VM(所以调试会话不产生 shard)。要产物就跑一次无调试器的编译(9.2 的 kotlinc 命令去掉 `-agentlib`) |

### 9.8 混编项目怎么调

- 样例里同时有 `*.java` 和 `*.kt` 时,**两个容器各自挂同一份 `/sources`**:
  `scip-debug`(5005,javac)+ `scip-debug-kotlin`(5006,kotlinc),互不干扰。
- 一次 IDEA 会话只能连一个 JVM → 想调哪种语言就 attach 哪个容器。
- 各自产物的 shard 都落在 `/Users/ydd/github/scip-sample/META-INF/scip/`,最终由 `AggregateRunner`
  合并成 `index.scip`(可 `scip-java index --output ...` 或直接看 shard)。

---

## 10. 附:脚本全文

### `debug-javac.sh`(容器内)

```bash
#!/usr/bin/env bash
# Debug launcher: runs javac with the scip compiler plugin suspended, waiting
# for a debugger on $DEBUG_PORT (default 5005).
#
# Usage inside the container:
#   bash debug-javac.sh [DEBUG_PORT] [JVM_VERSION]
set -eu

DEBUG_PORT="${1:-5005}"
if [ $# -gt 0 ]; then shift; fi
JVM_VERSION="${1:-21}"
if [ $# -gt 0 ]; then shift; fi

# 1. Pick a JDK (the image has 25/21/17 pre-installed via coursier).
eval "$(coursier java --jvm "$JVM_VERSION" --env --jvm-index https://github.com/coursier/jvm-index/blob/master/index.json)"

# 2. Locate the plugin jar: prefer a freshly-built one mounted at
#    /opt/plugin/scip-plugin.jar (built from YOUR repo code), otherwise fall
#    back to the one embedded in the image distribution jar.
PLUGIN_DIR=/tmp/scip-plugin
mkdir -p "$PLUGIN_DIR"
if [ -f /opt/plugin/scip-plugin.jar ]; then
  cp /opt/plugin/scip-plugin.jar "$PLUGIN_DIR/scip-plugin.jar"
else
  unzip -o /app/scip-java/lib/scip-java-*.jar scip-plugin.jar -d "$PLUGIN_DIR" >/dev/null || \
    unzip -o /app/scip-java/lib/scip-java-0.0.0-SNAPSHOT.jar scip-plugin.jar -d "$PLUGIN_DIR" >/dev/null
fi

# 3. Run javac with the plugin, suspended for the debugger.
echo "== Waiting for a debugger on port $DEBUG_PORT (attach e.g. 'jdb -attach localhost:$DEBUG_PORT')"
javac \
  -J-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:${DEBUG_PORT} \
  -J$(cat /app/scip-java/javac-jvm-options) \
  -processorpath "$PLUGIN_DIR/scip-plugin.jar" \
  "-Xplugin:scip -sourceroot:/sources -targetroot:/sources" \
  "$@"
```

### `dbg-attach.sh`(宿主机)

```bash
#!/usr/bin/env bash
# Host-side driver: attaches jdb to the suspended container, sets a breakpoint,
# resumes, and dumps the call stack + locals when the breakpoint is hit.
set -u
FIFO=/tmp/opencode/dbg.fifo
OUT=/tmp/opencode/jdb.out
BP="${1:-org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor.resolveMethodTree}"

rm -f "$FIFO" "$OUT"
mkfifo "$FIFO"

jdb -attach localhost:5005 < "$FIFO" > "$OUT" 2>&1 &
JDBPID=$!

exec 3> "$FIFO"   # opens writer; unblocks jdb's read

send() { echo "$1" >&3; sleep 0.6; }

send "stop in $BP"
send "resume"

# wait for the breakpoint hit (jdb in a Chinese locale prints 断点命中)
for _ in $(seq 1 90); do
  grep -q "断点命中" "$OUT" && break
  sleep 1
done

echo "===== after resume / breakpoint hit ====="
cat "$OUT"

if grep -q "断点命中" "$OUT"; then
  send "where"
  sleep 0.6
  send "locals"
  sleep 0.6
fi
send "quit"
exec 3>&-

# give jdb a moment to write final output, then make sure the process ends
sleep 2
kill "$JDBPID" 2>/dev/null

echo ""
echo "===== final (where / locals) ====="
cat "$OUT"
```

> 为什么要用 FIFO 而不是直接管道?jdb 读 stdin 是"有命令就读",直接管道会把 `where`/`locals` 在断点命中前就吞掉执行。FIFO 由脚本按节奏喂命令,保证命令都是在断点命中后执行。

---

## 11. 速查:每次调试的三条命令

```bash
# ① 改了代码?重建插件 jar(增量很快)
#    ...第 4.3 节的两条命令...

# ② 起调试容器(挂载新 jar)
docker rm -f scip-debug
docker run -d --name scip-debug -p 5005:5005 \
  -v /Users/ydd/github/scip-sample:/sources \
  -v /Users/ydd/github/scip-java/scip-javac/target/gradle/libs/scip-javac-0.0.0-SNAPSHOT-all.jar:/opt/plugin/scip-plugin.jar \
  ghcr.io/scip-code/scip-java:latest \
  bash -lc 'cd /sources && bash debug-javac.sh 5005 21 src/main/java/example/*.java'

# ③ attach 打断点
bash /Users/ydd/github/scip-java/scripts/dbg-attach.sh \
  org.scip_code.scip_java.shaded.org.scip_code.scip_java.javac.ScipVisitor.resolveMethodTree
```