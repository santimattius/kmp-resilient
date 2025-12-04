package com.santimattius.kmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform