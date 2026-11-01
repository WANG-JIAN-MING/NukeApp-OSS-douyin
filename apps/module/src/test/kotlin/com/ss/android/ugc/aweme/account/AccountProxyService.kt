package com.ss.android.ugc.aweme.account

// Test stand-in for the host's account facade that the recall notice asks for the signed-in user.
object AccountProxyService {
    class UserService(var login: Boolean = true, var userId: String = "42") {
        fun isLogin(): Boolean = login
        fun getCurUserId(): String = userId
    }

    @JvmField var service = UserService()

    @JvmStatic fun userService(): UserService = service
}
