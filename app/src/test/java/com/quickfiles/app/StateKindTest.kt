package com.quickfiles.app

import com.quickfiles.app.ui.StateKind
import com.quickfiles.app.ui.errorKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Folder states must pick the right recovery for the providers' fixed error wording. */
class StateKindTest {
    @Test fun certificateFailuresNeverLookLikeLoginOrNetworkProblems() {
        assertEquals(StateKind.CERTIFICATE, errorKind("인증서를 확인할 수 없습니다. 서버의 인증서 신뢰, 접속 주소 일치 여부와 유효기간을 확인해 주세요. 보안 검사를 건너뛰지 않습니다."))
    }

    @Test fun authenticationAndStoredPasswordFailuresOfferLogin() {
        assertEquals(StateKind.AUTH, errorKind("인증에 실패했습니다. 사용자 이름과 비밀번호를 확인해 주세요."))
        assertEquals(StateKind.AUTH, errorKind("저장한 비밀번호를 읽을 수 없습니다. 연결을 수정해 다시 입력해 주세요."))
    }

    @Test fun phonePermissionAndProtectedFoldersOfferPermission() {
        assertEquals(StateKind.PERMISSION, errorKind("휴대폰의 ‘모든 파일 접근’ 권한을 허용해 주세요."))
        assertEquals(StateKind.PERMISSION, errorKind("Android에서 보호하는 폴더입니다."))
    }

    @Test fun connectionFailuresOfferRetry() {
        assertEquals(StateKind.NETWORK, errorKind("서버에 연결하지 못했습니다. 주소와 네트워크를 확인한 뒤 다시 시도해 주세요."))
        assertEquals(StateKind.NETWORK, errorKind("서버가 일시적으로 요청을 처리하지 못했습니다. (HTTP 503)"))
    }

    @Test fun unknownWordingFallsBackToGenericFailure() {
        assertEquals(StateKind.FAILED, errorKind("폴더를 찾을 수 없습니다."))
    }
}
