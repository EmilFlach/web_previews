package com.emilflach.web_previews

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform