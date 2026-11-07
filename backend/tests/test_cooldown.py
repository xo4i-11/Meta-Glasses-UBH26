from app.cooldown import Cooldown


def test_cooldown():
    c = Cooldown(10)
    assert c.allow("stop", 0) and not c.allow("stop", 9.9) and c.allow("stop", 10.1)
    assert c.allow("duck", 5)  # different key independent
