#!/usr/bin/env python3
"""Writes a realistic Atlas state file for the emulator smoke test. Usage: seed_state.py NOW_MS THEME OUT"""
import json, sys, uuid
now, theme, out = int(sys.argv[1]), sys.argv[2], sys.argv[3]
M = 60_000
def task(title, start, dur, status, **kw):
    t = dict(id=str(uuid.uuid4()), title=title, notes=kw.get("notes", ""), priority=kw.get("priority", "normal"),
             startAt=start, durationMin=dur, recurrence=kw.get("recurrence", "none"), status=status,
             createdAt=now - 120 * M, updatedAt=now - 60 * M, version=2, deviceId="seed", completedAt=kw.get("completedAt"),
             blockedReason=kw.get("blockedReason", ""), skipReason="", seriesId=None,
             remindedAt=kw.get("remindedAt"), nextCheckAt=kw.get("nextCheckAt"), promptedAt=kw.get("promptedAt"), followUpsSent=0)
    return t
alarm_now = theme.endswith("-alarm")
theme = theme.replace("-alarm", "")
tasks = [
    task("Read 20 pages", now - 50 * M, 30, "awaiting", remindedAt=now - 50 * M, nextCheckAt=now - 20 * M, promptedAt=now - 20 * M),
    task("Code Atlas", now - 10 * M, 90, "active", remindedAt=now - 10 * M, nextCheckAt=now + 80 * M, priority="high"),
    task("Gym", now + 120 * M, 60, "planned", recurrence="daily"),
    task("Call mum", now + 26 * 60 * M, 15, "planned"),
    task("Plan the week", None, 30, "planned"),
    task("Fix bike", None, 45, "blocked", blockedReason="Waiting for the part"),
    task("Pray", now - 200 * M, 15, "done", completedAt=now - 185 * M),
]
if alarm_now:
    # one reminder that is due right now, for the alarm test
    tasks.append(task("Pick up my sister", now - 20 * 1000, 15, "planned", priority="high"))   # important: rings the alarm
    tasks.append(task("Drink water", now - 15 * 1000, 5, "planned"))                           # lighter: a notification
state = dict(schema=1, deviceId="seed",
             settings=dict(name="Anjy", tone="firm", quietStart="23:59", quietEnd="00:00", graceMin=15, followUps=1,
                           sound=True, avatarOn=True, speakReplies=False, theme=theme, wakeWord=True),
             tasks=tasks,
             log=[dict(id=str(uuid.uuid4()), at=now - 20 * M, type="checkin", taskId=tasks[0]["id"], title="Read 20 pages", detail="")],
             memories=[dict(id=str(uuid.uuid4()), text="My chess goal is 1500", createdAt=now - 300 * M)])
json.dump(state, open(out, "w"))
