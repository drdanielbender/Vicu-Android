package com.rendyhd.vicu.util

fun randomUuid(): String {
    return (1..32).map { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
}
