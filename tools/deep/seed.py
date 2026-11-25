#!/usr/bin/env python3
"""
Odd but realistic data for the deep test rig: reminders, memories and a chat in many scripts and sizes, with markup
that must stay text, a very long title, an empty-ish one, times on both sides of midnight and a DST change.
Usage: seed.py NOW_MS OUTDIR   → OUTDIR/atlas-state.json (phone: files/atlas-state.json, PC: state.json) and
OUTDIR/chat.json (both: chat.json)
"""
import json, os, sys, uuid
now, out = int(sys.argv[1]), sys.argv[2]
os.makedirs(out, exist_ok=True)
M = 60_000
ODD = ["Ünïcødé ✓ café", "漢字のリマインダー", "اتصل بأمي (right to left)", "עברית בדיקה", "हिन्दी परीक्षण", "👨‍👩‍👧‍👦 family 🎉🎂",
       "<script>window.__xss=1</script><b>bold?</b>", "Robert'); DROP TABLE tasks;--", "a" * 400, " ", "Zalgo T̸̢͖̈́e̵͙͝s̷̰̈t̴͎̆"]


def task(title, start, dur, status, **kw):
    return dict(id=str(uuid.uuid4()), title=title, notes=kw.get("notes", ""), priority=kw.get("priority", "normal"),
                startAt=start, durationMin=dur, recurrence=kw.get("recurrence", "none"), status=status,
                createdAt=now - 120 * M, updatedAt=now - 60 * M, version=2, deviceId="seed", completedAt=kw.get("completedAt"),
                blockedReason=kw.get("blockedReason", ""), skipReason="", seriesId=None,
                remindedAt=kw.get("remindedAt"), nextCheckAt=kw.get("nextCheckAt"), promptedAt=kw.get("promptedAt"), followUpsSent=0)


tasks = [
    task("Read 20 pages", now - 50 * M, 30, "awaiting", remindedAt=now - 50 * M, nextCheckAt=now - 20 * M, promptedAt=now - 20 * M),
    task("Code Atlas", now - 10 * M, 90, "active", remindedAt=now - 10 * M, nextCheckAt=now + 80 * M, priority="high"),
    task("Gym", now + 120 * M, 60, "planned", recurrence="daily"),
    task("Call mum", now + 26 * 60 * M, 15, "planned", notes="<img src=x onerror=alert(1)> and **markdown**"),
    task("Plan the week", None, 30, "planned"),
    task("Fix bike", None, 45, "blocked", blockedReason="Waiting for the part 🔧"),
    task("Pray", now - 200 * M, 15, "done", completedAt=now - 185 * M),
    task("Weekly review", now + 6 * 24 * 60 * M, 720, "planned", recurrence="weekly"),
    task("Long one", now + 3 * 60 * M, 720, "planned", recurrence="weekdays"),
]
for i, t in enumerate(ODD):
    tasks.append(task(t, now + (i + 3) * 37 * M if i % 3 else None, 5 + i * 10, ["planned", "done", "blocked", "skipped"][i % 4],
                      blockedReason="ünïcødé reason" if i % 4 == 2 else ""))
state = dict(schema=1, deviceId="seed",
             settings=dict(name="Anjy ✨", tone="firm", quietStart="23:59", quietEnd="00:00", graceMin=15, followUps=1,
                           sound=True, avatarOn=True, speakReplies=False, theme="dark", wakeWord=False),
             tasks=tasks,
             log=[dict(id=str(uuid.uuid4()), at=now - (i + 1) * 13 * M, type=["checkin", "done", "skipped", "note"][i % 4],
                       taskId=tasks[i % len(tasks)]["id"], title=tasks[i % len(tasks)]["title"], detail=ODD[i % len(ODD)][:60]) for i in range(40)],
             memories=[dict(id=str(uuid.uuid4()), text=t, createdAt=now - (i + 1) * 300 * M) for i, t in enumerate(
                 ["My chess goal is 1500", "I live in Lagos (UTC+1, no DST)", "مفضل: الشاي", "好きな食べ物: ラーメン 🍜"] + ODD)])
chat = []
for i in range(60):
    chat.append(dict(id=str(uuid.uuid4()), user=i % 2 == 0, source="You" if i % 2 == 0 else "Gemini", at=now - (60 - i) * 7 * M,
                     text=(ODD[i % len(ODD)] if i % 2 == 0 else
                           ["Sure — here's a **table**:\n\n| a | b |\n|---|---|\n| 1 | 2 |", "```js\nconsole.log('<b>hi</b>')\n```", "Done ✓", "Line one\nLine two\n\n- bullet\n- 漢字",
                            "x" * 3000, "[a link](javascript:alert(1)) and [another](https://example.com)"][i % 6]),
                     steps=["Searched the web: ünïcødé"] if i % 5 == 1 else [], media=[]))
json.dump(state, open(os.path.join(out, "atlas-state.json"), "w"), ensure_ascii=False)
json.dump(chat, open(os.path.join(out, "chat.json"), "w"), ensure_ascii=False)
