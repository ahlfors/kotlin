// TARGET_BACKEND: JVM
// WITH_REFLECT
// JAVAC_OPTIONS: -parameters
// FILE: J.java

public class J {
    public J(String constructorParam) {}

    public void foo(int methodParam) {}

    static void bar(J staticMethodParam) {}
}

// FILE: K.kt

import kotlin.test.assertEquals

fun box(): String {
    assertEquals(listOf("constructorParam"), ::J.parameters.map { it.name })
    assertEquals(listOf(null, "methodParam"), J::foo.parameters.map { it.name })
    assertEquals(listOf("staticMethodParam"), J::bar.parameters.map { it.name })

    return "OK"
}
