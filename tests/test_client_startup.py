"""client_startup.startup_failure の判定を確認するテスト。"""

from fukurou.runner.client_startup import startup_failure

OPENGL_FAILED = "[15:27:19] [Render thread/ERROR]: Failed to create backend OpenGL\n"
VULKAN_FAILED = "[15:27:19] [Render thread/ERROR]: Failed to create backend Vulkan\n"


def test_all_backends_failing_is_fatal():
    message = startup_failure("[15:27:16] [Render thread/INFO]: Setting user: Alice\n" + OPENGL_FAILED + VULKAN_FAILED)
    assert message is not None
    assert "OpenGL" in message and "Vulkan" in message


def test_one_backend_failing_is_not_fatal():
    assert startup_failure(OPENGL_FAILED) is None
    assert startup_failure(OPENGL_FAILED + "[15:27:20] [Render thread/INFO]: Using graphics backend Vulkan\n" + VULKAN_FAILED) is None


def test_fatal_lines_and_crashes():
    assert startup_failure("[12:00:00] [Render thread/FATAL]: Unreported exception thrown!\n") is not None
    assert startup_failure("#@!@# Game crashed! Crash report saved to: crash.txt\n") is not None


def test_normal_log_is_not_fatal():
    assert startup_failure("[12:00:00] [Render thread/INFO]: Connecting to 127.0.0.1, 25565\n") is None
