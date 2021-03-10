// MODULE: main
// MODULE_KIND: COMMON_JS
// FILE: lib.kt
@file:JsModule("hello")
package lib

@JsName("default")
external val foo: Int

@JsName("bar")
external object Bar {
    @JsName("default")
    val bar: Int
}

// FILE: main.kt
package main

import lib.*

fun box(): String {
    if (foo != 23 || Bar.bar != 45) return "fail"
    return "OK"
}

// FILE: hello.js

$kotlin_test_internal$.beginModule("hello");
module.exports = {
    "default": 23,
    "bar": {
        "default": 45
    }
}
$kotlin_test_internal$.endModule("hello");
