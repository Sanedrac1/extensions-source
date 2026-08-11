plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "TMOHentai"
    className = "TMOHentai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://tmohentai.app"
        host("tmohentai.app")
        host("tmohentai.com")
    }

    deeplink {
        host("tmohentai.app")
        host("tmohentai.com")
        path("/contents/.*")
        path("/library/manga/.*")
    }
}
