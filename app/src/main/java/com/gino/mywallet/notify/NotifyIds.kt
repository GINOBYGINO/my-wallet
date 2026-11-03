package com.gino.mywallet.notify

object NotifyIds {
    const val CHANNEL_LOTTERY = "lottery"
    const val CHANNEL_TASK = "task"
    const val CHANNEL_CHARITY = "charity"
    const val CHANNEL_COURSE = "course"
    const val CHANNEL_FOCUS = "focus"
    const val CHANNEL_EVENT = "event"
    const val CHANNEL_LOAN = "loan"

    const val NOTIF_LOTTERY = 1001
    const val NOTIF_LOTTO_DRAW = 1002
    const val NOTIF_TASK_BASE = 2000
    const val NOTIF_CHARITY = 3001
    const val NOTIF_COURSE_BASE = 4000
    const val NOTIF_FOCUS = 5001
    const val NOTIF_EVENT_BASE = 6000
    const val NOTIF_LOAN_BASE = 7000
    const val NOTIF_BOT_SYNC = 9001

    const val REQ_LOTTERY = 11001
    const val REQ_LOTTO_DRAW = 11002
    const val REQ_CHARITY_MONTHLY = 13001
    const val REQ_CHARITY_DAILY = 13002
    const val REQ_TASK_BASE = 12000
    const val REQ_COURSE_BASE = 14000
    const val REQ_EVENT_BASE = 15000
    const val REQ_LOAN_BASE = 16000
}

object NotifyActions {
    const val FIRE = "com.gino.mywallet.NOTIFY_FIRE"
    const val TYPE = "type"
    const val EXTRA_ID = "extra_id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"

    const val TYPE_LOTTERY = "lottery"
    const val TYPE_LOTTO_DRAW = "lotto_draw"
    const val TYPE_TASK = "task"
    const val TYPE_CHARITY = "charity"
    const val TYPE_COURSE = "course"
    const val TYPE_EVENT = "event"
    const val TYPE_LOAN = "loan"
}
