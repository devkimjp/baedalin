package kr.disys.baedalin

object KeyRecordingState {
    var recordingFunction: String? = null
    val isRecording get() = recordingFunction != null
}
