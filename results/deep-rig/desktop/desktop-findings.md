# Deep test — desktop

Sessions: nokey, gemini, openai, claude, twokeys; 164 screenshots; 11 min

Coverage: {"nokey":{"clicked":56,"typed":27,"screens":68,"overlays":15},"gemini":{"clicked":55,"typed":23,"screens":67,"overlays":16},"openai":{"clicked":0,"typed":0,"screens":12,"overlays":0},"claude":{"clicked":0,"typed":0,"screens":10,"overlays":0},"twokeys":{"clicked":0,"typed":0,"screens":7,"overlays":0}}

## main-stderr (51)
- ×10 [nokey] [2583:1125/194808.679093:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0702000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: start
- ×9 [gemini] [2822:1125/195055.133387:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: destructive "Remove" on me (nokey)
- ×6 [nokey] [2583:1125/194808.731018:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: start
- ×3 [gemini] [2822:1125/195055.178807:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: destructive "Remove" on me (nokey)
- ×3 [openai] [3100:1125/195521.247626:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A070200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×3 [openai,claude,twokeys] [3100:1125/195654.602313:WARNING:connection.cc(58)] X error received.  Request: Shm::PutImageRequest, Error: DrawableError{.sequence = 5828, .bad_value = 2097155, .minor_opcode = 3, .major_opcode = 130}
    after: ask, then Esc while waiting (openai)
- ×3 [claude] [3252:1125/195655.798505:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0702000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×3 [twokeys] [3405:1125/195816.009351:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)
- ×2 [nokey] [2583:1125/194902.569079:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0B08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "Speak" [div#go.round.go] on atlas (nokey)
- ×2 [nokey] [2583:1125/194903.239043:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "Talk to Atlas" [div.orb] on atlas (nokey)
- ×1 [nokey] [2583:1125/194907.130270:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0C07C01EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: click "" [div.orb] on atlas (nokey)
- ×1 [openai] [3100:1125/195521.289917:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A0200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×1 [openai] [3100:1125/195524.265210:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0F0870064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: sheet primary "Save" (gemini)
- ×1 [claude] [3252:1125/195655.843836:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×1 [claude] [3252:1125/195700.683323:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (openai)
- ×1 [twokeys] [3405:1125/195816.055514:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)
- ×1 [twokeys] [3405:1125/195819.021050:ERROR:gles2_cmd_decoder_passthrough.cc(1064)] [GroupMarkerNotSet(crbug.com/242999)!:A0C086000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content.
    after: ask, then Esc while waiting (claude)

## console-warning (51)
- ×10 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0702000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: start
- ×9 [gemini] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: destructive "Remove" on me (nokey)
- ×6 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: start
- ×3 [gemini] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C1F0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: destructive "Remove" on me (nokey)
- ×3 [gemini] The ScriptProcessorNode is deprecated. Use AudioWorkletNode instead. (https://bit.ly/audio-worklet) (voice.js:397)
    after: HUD click "Talk to Atlas (Space)" (gemini)
- ×3 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A070200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×3 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0702000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×3 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A07020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)
- ×2 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0B08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "Speak" [div#go.round.go] on atlas (nokey)
- ×2 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "Talk to Atlas" [div.orb] on atlas (nokey)
- ×1 [nokey] [GroupMarkerNotSet(crbug.com/242999)!:A0C07C01EC010000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: click "" [div.orb] on atlas (nokey)
- ×1 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A0A0200064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×1 [openai] [GroupMarkerNotSet(crbug.com/242999)!:A0F0870064320000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: sheet primary "Save" (gemini)
- ×1 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0A02000D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×1 [claude] [GroupMarkerNotSet(crbug.com/242999)!:A0E08700D4100000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (openai)
- ×1 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A0A020000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)
- ×1 [twokeys] [GroupMarkerNotSet(crbug.com/242999)!:A0C086000C3E0000]Automatic fallback to software WebGL has been deprecated. Please use the --enable-unsafe-swiftshader flag to opt in to lower security guarantees for trusted content. (index.html:0)
    after: ask, then Esc while waiting (claude)

## Replies for each stand-in answer

- **gemini:ok** → "Stub reply: all good. Привет 👋\n\nGemini\nCopy\nRemember\nAsk Again" (0085-gemini-reply-ok.png)
- **gemini:md** → "Bold, italic, code and a link.\n\nDay\tPlan\nMon\tGym 🏋️\nTue\tRead 📚\nÜnïcødé: Ἀθηνᾶ, 漢字, العربية (right to left), עברית, हिन्दी, 👍🏽👨‍👩‍👧‍👦\nA long unbroken word: SupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilis" (0086-gemini-reply-md.png)
- **gemini:long** → "This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer to check scrolling, wrapping and memory. This is a very long answer t" (0087-gemini-reply-long.png)
- **gemini:429** → "Gemini asked me to wait 37 s (1 of 3)\nGemini" (0088-gemini-reply-429.png)
- **gemini:500** → "Gemini is busy, trying again (3 of 3)\nGemini" (0089-gemini-reply-500.png)
- **gemini:503** → "Gemini is busy, trying again (3 of 3)\nGemini" (0090-gemini-reply-503.png)
- **gemini:400key** → "That Gemini key isn't valid. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0091-gemini-reply-400key.png)
- **gemini:403** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0092-gemini-reply-403.png)
- **gemini:html502** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0093-gemini-reply-html502.png)
- **gemini:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0094-gemini-reply-malformed.png)
- **gemini:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0095-gemini-reply-empty.png)
- **gemini:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0096-gemini-reply-maxtokens.png)
- **gemini:slow** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0097-gemini-reply-slow.png)
- **gemini:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0098-gemini-esc-stop.png)
- **openai:ok** → "Stub reply: all good. Привет 👋\n\nOpenAI\nCopy\nRemember\nAsk Again" (0137-openai-reply-ok.png)
- **openai:md** → "Bold, italic, code and a link.\n\nDay\tPlan\nMon\tGym 🏋️\nTue\tRead 📚\nÜnïcødé: Ἀθηνᾶ, 漢字, العربية (right to left), עברית, हिन्दी, 👍🏽👨‍👩‍👧‍👦\nA long unbroken word: SupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilisticSupercalifragilis" (0138-openai-reply-md.png)
- **openai:429** → "Waiting for OpenAI's limit, 15 s (2 of 3)\nOpenAI" (0139-openai-reply-429.png)
- **openai:500** → "OpenAI is busy, trying again (3 of 3)\nOpenAI" (0140-openai-reply-500.png)
- **openai:400key** → "OpenAI turned the key down: Incorrect API key provided: sk-deep****. You can find your API key at https://platform.openai.com/account/api-keys. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0141-openai-reply-400key.png)
- **openai:html502** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0142-openai-reply-html502.png)
- **openai:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0143-openai-reply-malformed.png)
- **openai:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0144-openai-reply-empty.png)
- **openai:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0145-openai-reply-maxtokens.png)
- **openai:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0146-openai-esc-stop.png)
- **claude:ok** → "Stub reply: all good. Привет 👋\n\nClaude\nCopy\nRemember\nAsk Again" (0149-claude-reply-ok.png)
- **claude:429** → "Waiting for Claude's limit, 15 s (2 of 3)\nClaude" (0150-claude-reply-429.png)
- **claude:503** → "Claude is having trouble right now (529).\n\nCopy\nRemember\nAsk Again" (0151-claude-reply-503.png)
- **claude:400key** → "Claude turned the key down: invalid x-api-key (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0152-claude-reply-400key.png)
- **claude:malformed** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0153-claude-reply-malformed.png)
- **claude:empty** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0154-claude-reply-empty.png)
- **claude:maxtokens** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0155-claude-reply-maxtokens.png)
- **claude:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0156-claude-esc-stop.png)
- **twokeys:429** → "Gemini asked me to wait 37 s (1 of 3)\nGemini" (0159-twokeys-reply-429.png)
- **twokeys:ok** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0162-twokeys-reply-ok.png)
- **twokeys:400key** → "That Gemini key isn't valid. (No other key is ready: add more in Me → Brain.)\n\nCopy\nRemember\nAsk Again" (0161-twokeys-reply-400key.png)
- **twokeys:esc-stop** → "Add an AI key in Me → Brain (Gemini's is free), or start a local model, so I can think.\n\nCopy\nRemember\nAsk Again" (0163-twokeys-esc-stop.png)

## Destructive buttons

- nokey bots: "Remove From List" — not found again ()
- nokey me: "9999••••9999 In use Try Again Remove" — nothing visible happened (0066-nokey-danger-9999-9999-In-use-Try-Again-Remove.png)
- nokey me: "Remove" — asked first (0067-nokey-danger-Remove.png)
- gemini atlas: "Remove" — not found again ()
- gemini bots: "Remove From List" — not found again ()
- gemini me: "AIza••••0000 In use Try Again Remove" — not found again ()
