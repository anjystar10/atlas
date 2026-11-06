# Orb preview

Renders `app/src/main/assets/orb.frag` (the shader the app draws Atlas's orb with) on a computer, using
software OpenGL, so the design can be judged before building the app.

    pip install moderngl numpy pillow
    python3 tools/orb/sheet.py app/src/main/assets/orb.frag sheet.png    # rest, listening, small sizes, light theme
    python3 tools/orb/motion.py app/src/main/assets/orb.frag motion.png  # 12 s: rest, "Hey Atlas", talking, rest
    python3 tools/orb/dbg.py app/src/main/assets/orb.frag dbg.png "hug" "L1*0.5"   # look at one ingredient

`OrbPainter.kt` is a line-for-line copy for phones whose graphics chip can't draw the shader; keep them in step.
