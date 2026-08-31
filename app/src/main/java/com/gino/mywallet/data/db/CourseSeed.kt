package com.gino.mywallet.data.db

import com.gino.mywallet.data.entity.CourseEntity

/** 首次安裝的範例課表；fork 後請改成自己的課程，或回傳 emptyList() */
fun defaultCourses(): List<CourseEntity> = listOf(
    CourseEntity(
        title = "範例課程 A",
        weekday = 1,
        periodStart = 3,
        periodEnd = 4,
        location = "教學大樓 301"
    ),
    CourseEntity(
        title = "範例課程 B",
        weekday = 3,
        periodStart = 5,
        periodEnd = 6,
        location = "圖書館研習室"
    )
)
