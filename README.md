<div align="center"><h1>I4399Ocr</h1></div>

WPLauncherHelper 是一个使用 KMP (kotlin 跨平台) 实现的 4399 验证码识别库

从由易语言编写的 `WmCode.dll` 移植, 使用kotlin重写提升跨平台兼容性, 并去除了加密模型以提升性能

## 作为库使用

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // 把 ${version} 替换成最新版本
    implementation("io.github.kawaiinekochanproject:i4399-ocr:${version}")
}
```

## 快速开始

这是一个简单的示范 适用于 `kotlin/jvm`

```kotlin
import net.nekocurit.i4399_ocr.I4399Ocr
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun main(vararg args: String) {
    val image = get("https://ptlogin.4399.com/ptlogin/captcha.do?captchaId=1")

    File("captcha.jpeg").writeBytes(image)
    println(I4399Ocr.ocr(image))
}

fun get(url: String): ByteArray = HttpClient.newHttpClient().send(
    HttpRequest.newBuilder(URI.create(url)).build(),
    HttpResponse.BodyHandlers.ofByteArray()
).body()
```

## Issues

如果你发现了 bug 或者需要新的功能, 你可以创建一个新的 issue

我们也欢迎您提交 pull request
