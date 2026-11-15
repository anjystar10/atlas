import json, sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
def ms(tz, s):
    d = datetime.strptime(s, "%Y-%m-%d %H:%M").replace(tzinfo=ZoneInfo(tz))
    return int(d.astimezone(timezone.utc).timestamp() * 1000)
L = "Africa/Lagos"; NY = "America/New_York"; LO = "Europe/London"
nowL = "2026-11-12 18:20"
cases = [
 # [tz, now, input, expected local or null, error kind]
 (L, nowL, "2026-11-14 18:00", "2026-11-14 18:00"),
 (L, nowL, "2026-11-14T09:05", "2026-11-14 09:05"),
 (L, nowL, "2026-11-14 09:05:30", "2026-11-14 09:05"),
 (L, nowL, "2027-02-29 10:00", None, "isn't a real date"),
 (L, nowL, "2028-02-29 10:00", "2028-02-29 10:00"),
 (L, nowL, "2026-04-31 10:00", None, "isn't a real date"),
 (L, nowL, "2026-11-14 25:00", None, "isn't a real time"),
 (L, nowL, "2026-11-14", None, "no time"),
 (L, nowL, "in 90 minutes", "2026-11-12 19:50"),
 (L, nowL, "in 2 hours", "2026-11-12 20:20"),
 (L, nowL, "in 1h30m", "2026-11-12 19:50"),
 (L, nowL, "in 1 hour and 30 minutes", "2026-11-12 19:50"),
 (L, nowL, "in half an hour", "2026-11-12 18:50"),
 (L, nowL, "in an hour", "2026-11-12 19:20"),
 (L, nowL, "in 3 days", "2026-11-15 18:20"),
 (L, nowL, "tomorrow at 6pm", "2026-11-13 18:00"),
 (L, nowL, "tomorrow 6:30 pm", "2026-11-13 18:30"),
 (L, nowL, "Tomorrow at 12am", "2026-11-13 00:00"),
 (L, nowL, "tomorrow at 12pm", "2026-11-13 12:00"),
 (L, nowL, "today 8pm", "2026-11-12 20:00"),
 (L, nowL, "today at 9am", None, "already passed"),
 (L, nowL, "tonight at 9", "2026-11-12 21:00"),
 (L, nowL, "6pm", "2026-11-13 18:00"),
 (L, nowL, "9:30pm", "2026-11-12 21:30"),
 (L, nowL, "18:00", "2026-11-13 18:00"),
 (L, nowL, "noon", "2026-11-13 12:00"),
 (L, nowL, "monday 9am", "2026-11-16 09:00"),
 (L, nowL, "Monday at 9:00", "2026-11-16 09:00"),
 (L, nowL, "thursday 8pm", "2026-11-12 20:00"),
 (L, nowL, "thursday 9am", "2026-11-19 09:00"),
 (L, nowL, "next thursday at 8pm", "2026-11-19 20:00"),
 (L, nowL, "fri noon", "2026-11-13 12:00"),
 (L, nowL, "on Saturday at 10:15am", "2026-11-14 10:15"),
 (L, nowL, "tomorrow at 7", None, "morning or the evening"),
 (L, nowL, "tomorrow morning 7", "2026-11-13 07:00"),
 (L, nowL, "tomorrow", None, "no time"),
 (L, nowL, "13pm", None, "isn't a real time"),
 (L, nowL, "2026-11-12 18:00", None, "already passed"),
 (L, nowL, "2026-11-12 18:20", "2026-11-12 18:20"),
 (L, nowL, "2035-01-01 10:00", None, "five years"),
 (L, nowL, "someday", None, "couldn't read"),
 (L, nowL, "", None, "no time was given"),
 (NY, "2026-03-07 12:00", "2026-03-08 02:30", "2026-03-08 03:30"),
 (NY, "2026-03-07 12:00", "tomorrow at 2:30am", "2026-03-08 03:30"),
 (NY, "2026-10-31 12:00", "2026-11-01 01:30", "2026-11-01 01:30"),
 (LO, "2026-03-28 10:00", "in 24 hours", "2026-03-29 11:00"),
 (LO, "2026-03-28 10:00", "tomorrow 10am", "2026-03-29 10:00"),
]
out = []
for c in cases:
    tz, now, inp, exp = c[0], c[1], c[2], c[3]
    row = {"tz": tz, "now": ms(tz, now), "nowLocal": now, "input": inp}
    if exp is None: row["error"] = c[4]
    else:
        row["local"] = exp
        # expected instant: the overlap takes the earlier offset (fold=0); the gap case is checked by its local form
        row["ms"] = ms(tz, exp)
        if inp.startswith("in "):
            # elapsed time: exact, independent of clock changes
            import re
            row["ms"] = row["now"] + {"in 90 minutes": 90, "in 2 hours": 120, "in 1h30m": 90, "in 1 hour and 30 minutes": 90, "in half an hour": 30, "in an hour": 60, "in 3 days": 4320, "in 24 hours": 1440}[inp] * 60000
    out.append(row)
# overlap must be the EARLIER moment (EDT, UTC-4): 05:30Z
for r in out:
    if r["input"] == "2026-11-01 01:30": assert r["ms"] == int(datetime(2026,11,1,5,30,tzinfo=timezone.utc).timestamp()*1000)
repeats = [["", "none"], ["none", "none"], ["Daily", "daily"], ["every day", "daily"], ["weekdays", "weekdays"], ["every weekday", "weekdays"], ["Weekly", "weekly"], ["monthly", None], ["every 2 days", None]]
minutes = [[None, 1, 1440, None], ["30", 1, 1440, 30], [45, 1, 1440, 45], ["1.5 hours", 1, 1440, 90], ["2h", 1, 1440, 120], ["0", 1, 1440, "NaN"], ["5000", 1, 1440, "NaN"], ["soon", 1, 1440, "NaN"]]
clocks = [["6pm", [18, 0]], ["6 pm", [18, 0]], ["12am", [0, 0]], ["12pm", [12, 0]], ["12:30 a.m.", [0, 30]], ["18.45", [18, 45]], ["0930", [9, 30]], ["midnight", [0, 0]], ["24:00", "bad"], ["13pm", "bad"], ["0pm", "bad"], ["7", None]]
json.dump({"_about": "Shared accuracy cases: the PC (desktop/test/accuracy.test.mjs) and the phone (AccuracyTest.kt) must give exactly these answers. Expected instants were computed independently with Python's zoneinfo. Regenerate with the script described in the test header.",
           "when": out, "repeat": repeats, "minutes": minutes, "clock": clocks}, open(sys.argv[1], "w"), indent=1, ensure_ascii=False)
print(len(out), "when cases")
