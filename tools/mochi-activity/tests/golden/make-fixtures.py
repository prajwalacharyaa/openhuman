#!/usr/bin/env python3
"""Writes the synthetic trees the golden test replays (no real data: everything here is made up).

After changing them, regenerate the expected output on the server with the Python reference:
  for c in tests/golden/*/; do /root/tiny-setup/scripts/activity-golden replay "${c%/}/root" > "${c}expected.txt"; done
"""
import json
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REPOS = "/home/openhuman/OpenHuman/projects/github/"
P = "/home/openhuman/.claude/projects/-home-openhuman-OpenHuman-projects"


def write(root, path, text, mode="w"):
    full = root + path
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, mode) as f:
        f.write(text)


def jl(*entries, nl="\n"):
    return "".join((e if isinstance(e, str) else json.dumps(e)) + nl for e in entries)


def user(content, **kw):
    return {"type": "user", "message": {"role": "user", "content": content}, **kw}


def asst(mid, content, stop="tool_use", model="claude-opus-4-5", usage=True, cwd=REPOS + "site"):
    m = {"id": mid, "model": model, "role": "assistant", "content": content, "stop_reason": stop}
    if usage:
        m["usage"] = {"input_tokens": 12, "output_tokens": 340, "cache_read_input_tokens": 51200,
                      "cache_creation_input_tokens": 0}
    return {"type": "assistant", "cwd": cwd, "message": m}


def tool(tid, name, inp):
    return {"type": "tool_use", "id": tid, "name": name, "input": inp}


def result(tid, content):
    return {"type": "tool_result", "tool_use_id": tid, "content": content}


def common(root, agent_notify):
    write(root, "/home/openhuman/OpenHuman/core-auth.conf", 'OPENHUMAN_CORE_TOKEN="fake-token"\n')
    write(root, "/etc/mochi/agents.json", json.dumps({"mark": {"notify": agent_notify}, "sy": {"notify": False},
                                                      "melissa": {"color": "x"}}))
    for a in ("mark", "sy", "melissa"):
        write(root, "/home/openhuman/OpenHuman/projects/agent-notes/" + a + "/.keep", "")  # git keeps no empty folder
    write(root, "/home/openhuman/OpenHuman/.openhuman/claude-code-sessions.json", json.dumps({"sessions": {
        "thread-a~claude": "s1", "thread-c~claude": "s3", "voice-123~x": "s4", "thread-b": "s2"}}))
    write(root, "/home/openhuman/OpenHuman/workspace/memory/conversations/threads.jsonl", jl(
        {"thread_id": "thread-a", "labels": ["work"]},
        {"thread_id": "thread-c", "labels": ["x", "agent:melissa"]},
        "not json but thread-c \"labels\"",
        {"thread_id": "thread-cc", "labels": ["agent:other"]}))


def chat(root):
    common(root, True)
    long_words = ("Please check the site token=abc123 and the ünïcode words that go on and on and on for a while so "
                  "that the cut at one hundred and ten characters is reached somewhere here")
    s1 = jl(
        "not json at all",
        {"type": "queue-operation", "operation": "enqueue"},
        user("Current Date & Time: Friday\n\n[Voice recap: earlier]\n\n" + long_words +
             "\n<memory-context>private memory</memory-context>"),
        asst("m1", [{"type": "thinking", "thinking": "hmm"},
                    {"type": "text", "text": "## Let me look\nMore words"},
                    tool("t1", "Bash", {"command": "cd ~/OpenHuman/projects/github/site && GIT_X=1 git status --short"})]),
        user([result("t1", "M a.js\nM b.js\n")]),
        asst("m1", [tool("t2", "Read", {"file_path": REPOS + "blog/src/app.js"}),
                    tool("t3", "Grep", {"pattern": "TODO|FIXME and a very long pattern indeed", "path": REPOS + "blog"}),
                    tool("t4", "WebSearch", {"query": "nepse  market\nhours today"}),
                    tool("t5", "WebFetch", {"url": "https://example.com/a/b?x=1"}),
                    tool("t6", "mcp__openhuman__memory_search", {"query": "december birthday"}),
                    tool("t7", "mcp__openhuman__memory_store", {"text": "x"}),
                    tool("t8", "Task", {"subagent_type": "mark", "description": "Mark rewrites the intro",
                                        "prompt": "long prompt"}),
                    tool("t9", "Skill", {"skill": "pdf"}),
                    tool("t10", "TodoWrite", {"todos": []}),
                    tool("t11", "Glob", {"pattern": "**/*.rs"}),
                    tool("t12", "Edit", {"file_path": "", "notebook_path": "/x/nb.ipynb/"}),
                    tool("t13", "ToolSearch", {"query": "select:x"}),
                    {"type": "tool_use", "id": "t14", "input": {}},
                    tool("t15", "mcp__openhuman__send_THING_now", None),
                    tool("t16", "Bash", {"command": "mochi-phone contacts find Ram"}),
                    tool("t17", "Bash", {"command": "curl -H 'Authorization: Bearer sk-live-123' https://api.x/y?api_key=zz"}),
                    tool("t18", "Bash", {"command": "python3 -m pytest -q tests"}),
                    tool("t19", "Bash", {"command": "FOO=1 BAR=2 npm run build"}),
                    tool("t20", "Bash", {"command": "mochi-git push mochi/fix --draft"}),
                    tool("t21", "Bash", {"command": "   "}),
                    tool("t22", "Task", {"prompt": None}),
                    tool("t23", "Skill", {"name": "x", "skill": ""}),
                    tool("t24", "WebFetch", {"url": ""}),
                    tool("t26", "Agent", {"description": "", "prompt": "do the thing\nnow"}),
                    tool("t27", "Bash", {"command": "/usr/bin/env\u00a0x"}),
                    tool("t28", "Write", {"file_path": None, "content": {"weird": [1, 2.5, None, True, "it's"]}}),
                    tool("t29", "Bash", {"command": ["ls", "-la", {"k": 1e20, "q": "say \"hi\"", "x": "\u00e9\x01"}]}),
                    tool("t30", "Read", {"file_path": None}),
                    ]),
        user([result("t2", [{"type": "text", "text": "line1\nline2\n\nline3"}, "skip", {"type": "image"}]),
              result("t3", "a.rs:1: TODO fix\nb.rs:2: FIXME\n"),
              result("t4", json.dumps([{"title": "NEPSE hours"}, {"title": "ab"}, {"title": "Market today: open"},
                                       {"title": "Fourth"}])),
              result("t5", "<html>\n  # Title of page  \n" + "x" * 1500),
              result("t6", '[{"id": 1, "text": "birthday is in May"}, {"id" : 2}]'),
              result("t7", "stored"),
              result("t8", "Done: the intro is rewritten.\npassword=hunter2"),
              result("t9", ""),
              result("t11", "a.rs\nb.rs\nc.rs"),
              result("t12", None),
              result("t14", "x"),
              result("t15", {"ok": True}),
              result("t16", "Ram +977 9800000000"),
              result("t17", "HTTP/1.1 200\nsecret: abc"),
              result("t18", "3 passed"),
              result("t19", "\n\n"),
              result("t20", "pushed"),
              result("t99", "no step for this one"),
              result("t21", 0),
              result("t28", [{"type": "text", "text": None}, {"text": "  "}]),
              result("t29", "a\u2028b\x1cc\n\u00a0\n"),
              result("t26", [{"type": "text", "text": "> quoted\n- item"}]),
              ]),
        user("tool output looked fine", isMeta=True),
        asst("m2", [{"type": "text", "text": "All done: the site builds again. Bearer abc token: xyz\nsecond line"}],
             stop="end_turn"),
        user("hello there"),
        asst("m3", [], stop=None),
        asst("m4", [{"type": "text", "text": "```\ncode\n```"}], stop=None, model=None),
        asst("m4", [{"type": "text", "text": "  thinking aloud  "}], stop=None),
        asst("", [{"type": "text", "text": "   "}], stop="end_turn"),
        asst("m5", [{"type": "text", "text": "Short answer."}], stop="end_turn", model="claude-sonnet-4-5"),
        user([{"type": "text", "text": "[Hiring note] not an agent chat here"}]),
        user({"weird": "content"}),
        user(None),
        asst("m6", "a string content", stop="end_turn"),
    )
    write(root, P + "/s1.jsonl", s1)
    # subagents of s1
    sub = P + "/s1/subagents/"
    write(root, sub + "agent-a1.meta.json", json.dumps({"agentType": "mark", "description": "Mark rewrites the portfolio intro"}))
    write(root, sub + "agent-a1.jsonl", jl(
        user("Rewrite the intro"),
        asst("a1m1", [tool("a1t1", "Read", {"file_path": REPOS + "portfolio/index.html"})], cwd=REPOS + "portfolio"),
        user([result("a1t1", "x\ny")]),
        asst("a1m2", [{"type": "text", "text": "I can't do this, Mochi should handle it.\npassword: hunter2 stays hidden"}],
             stop="end_turn")))
    write(root, sub + "agent-a2.jsonl", jl(
        asst("a2m1", [{"type": "text", "text": "Working on it"}], stop=None),
        asst("a2m2", [{"type": "text", "text": "Finished"}], stop="end_turn")))
    write(root, sub + "agent-a3.meta.json", json.dumps({"agentType": "researcher2", "description": ""}))
    write(root, sub + "agent-a3.jsonl", jl(user([result("zz", "x")]),
                                           asst("a3m1", [{"type": "text", "text": "ok"}], stop="end_turn")))
    write(root, sub + "agent-a4.meta.json", json.dumps({"agentType": "builder"}))
    write(root, sub + "agent-a4.jsonl", jl(asst("a4m1", [{"type": "text", "text": "built"}], stop="end_turn")))
    write(root, sub + "agent-a5.meta.json", "{broken")
    write(root, sub + "agent-a5.jsonl", jl(asst("a5m1", [{"type": "text", "text": "hi"}], stop="end_turn")))
    write(root, sub + "agent-a6.meta.json", json.dumps({"agentType": "qa2", "description": "Second temporary"}))
    write(root, sub + "agent-a6.jsonl", jl(asst("a6m1", [tool("x", "Glob", {})])))
    # SY's direct chat: the agent from its agent-setting line
    write(root, P + "/s2.jsonl", jl(
        {"type": "agent-setting", "agentSetting": "sy"},
        user([{"type": "text", "text": "[Hiring candidate, from mochi-hire]\nDetails follow"}]),
        asst("s2m1", [{"type": "text", "text": "Reviewed the candidate."}], stop="end_turn")))
    # Melissa's direct chat: the agent from the chat's label; CRLF line ends and a raw CR inside
    write(root, P + "/s3.jsonl", jl(
        user("Write me a post"),
        asst("s3m1", [{"type": "text", "text": "Drafting now"}], stop=None),
        nl="\r\n") + '{"type": "user", "message": {"content": "a\rb"}}\n' + jl(
        asst("s3m2", [tool("s3t1", "Bash", {"command": "ls"})] * 1, stop="tool_use"),
        asst("s3m3", [{"type": "text", "text": "Posted."}], stop="end_turn")))
    # a voice chat: a long task never notifies
    write(root, P + "/s4.jsonl", jl(
        user("(Asked on a voice call at 10:01) what's on my calendar"),
        *[asst(f"s4m{i}", [tool(f"s4t{i}", "Bash", {"command": f"mochi-remind list {i}"})]) for i in range(6)],
        asst("s4mz", [{"type": "text", "text": "Three things."}], stop="end_turn")))
    # an agent-setting that isn't a valid name, and one past the first five lines
    write(root, P + "/s5.jsonl", jl({"type": "agent-setting", "agentSetting": "Bad Name"},
                                    asst("s5m1", [{"type": "text", "text": "x"}], stop="end_turn")))
    write(root, P + "/s6.jsonl", jl(*[{"type": "x"}] * 5, {"type": "agent-setting", "agentSetting": "mark"},
                                    asst("s6m1", [{"type": "text", "text": "late agent"}], stop="end_turn")))
    # ignored: hidden folders and files, other names
    write(root, "/home/openhuman/.claude/projects/.hidden/s9.jsonl", jl(user("hidden")))
    write(root, P + "/.s8.jsonl", jl(user("hidden too")))
    write(root, P + "/notes.txt", "x")


def call(root, end_call):
    common(root, True)
    lines = [
        "\x1b[2m2026-11-28T03:00:00Z\x1b[0m INFO [voice-live] result web_search: before any call",
        "[voice-live] session started (gemini)",
        "  INFO openhuman::voice: [voice-live] tool web_search: nepse   hours today  ",
        "[voice-live] result web_search: NEPSE is open 11:00-15:00",
        "[voice-live] tool memory_recall: december",
        "[voice-live] tool shell: mochi-phone sms send Ram hello",
        "[voice-live] result shell: sent to Ram",
        "[voice-live] tool shell: cd ~/OpenHuman/projects/github/site && git log -1",
        "[voice-live] result shell: ",
        "[voice-live] tool ask_claude: plan the week token=abc",
        "[voice-live] Claude's answer arrived",
        "[voice-live] tool hang_up_now: bye: really",
        "unrelated line\r[voice-live] Claude's answer arrived",
    ]
    if end_call:
        lines.append("[voice-live] session ended (12 s)")
    write(root, "/journal.txt", "\n".join(lines) + "\n")
    write(root, P + "/c1.jsonl", jl(
        user("Book the table"),
        *[asst(f"c1m{i}", [tool(f"c1t{i}", "Bash", {"command": "mochi-browse open x"})]) for i in range(5)],
        asst("c1mz", [{"type": "text", "text": "Booked."}], stop="end_turn")))
    sub = P + "/c1/subagents/"
    write(root, sub + "agent-b1.meta.json", json.dumps({"agentType": "mark", "description": "Mark checks"}))
    write(root, sub + "agent-b1.jsonl", jl(asst("b1m1", [{"type": "text", "text": "Checked: all good"}], stop="end_turn")))


if __name__ == "__main__":
    for name, build in (("chat", chat), ("call", lambda r: call(r, True)), ("on-call", lambda r: call(r, False))):
        root = os.path.join(HERE, name, "root")
        shutil.rmtree(root, ignore_errors=True)
        os.makedirs(root)
        build(root)
        print("wrote", root)
