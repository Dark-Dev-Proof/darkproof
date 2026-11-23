#!/usr/bin/env python3
"""Makes the vectors for replies, reactions and journey notes (spec/FORMAT.md, "Replies and reactions", "Journey
notes"): the `kind: about`, `kind: about-target`, `kind: reactions`, `kind: receipt-notes` and `kind: journey-note`
blocks at the end of spec/test-vectors.txt.

An independent implementation, written from the spec with Python's standard library only (hashlib, hmac, and AES-GCM
spelled out below). It shares no code with the apps. Run it with `--check` first: it then rebuilds the existing
`kind: message-key` vector from the spec and compares it byte for byte, which proves the sealing below is the apps'.

    python3 -I spec/make-reply-vectors.py --check
    python3 -I spec/make-reply-vectors.py > /tmp/new-vectors.txt

With `timer` as its argument it makes the vectors of "Disappearing messages" instead (`kind: timer-field`,
`timer-rekey`, `timer-epoch`, `timer-rival`, `timer-due`, `timer-notice`), which follow the others in the file:

    python3 -I spec/make-reply-vectors.py timer > /tmp/timer-vectors.txt

With `leave` it makes the vectors of "Leaving the group" (`kind: leave-field`, `leave-note`, `leave-valid`,
`leave-admin`, `leave-after`), which follow the timer's:

    python3 -I spec/make-reply-vectors.py leave > /tmp/leave-vectors.txt

With `quiet` it makes the vectors of "If I go quiet" (`kind: quiet-field`, `quiet-message`, `quiet-valid`,
`quiet-pick`, `quiet-cancel`, `quiet-late`, `quiet-text`), which follow the leave note's:

    python3 -I spec/make-reply-vectors.py quiet > /tmp/quiet-vectors.txt

With `voice` it makes the vectors of "Voice notes" (`kind: voice-field`, `voice-trim`, `voice-message`), which follow
the check-in clock's. `voice-trim` reads one of the codec experiment's Apple-made fields (its SHA-256 is checked), by
default from ios/BlackoutCore/Tests/voice; another path may follow `voice`:

    python3 -I spec/make-reply-vectors.py voice > /tmp/voice-vectors.txt

With `finder` it makes the vectors of "Finder" (`kind: finder-field`, `finder-fit`, `finder-bands`), which follow the
voice notes':

    python3 -I spec/make-reply-vectors.py finder > /tmp/finder-vectors.txt
"""
import hashlib, hmac, os, struct, sys

# ---- AES-256-GCM (FIPS 197, NIST SP 800-38D), 12-byte IV, 16-byte tag ----
SBOX = []
def _sbox():
    p = q = 1
    box = [0] * 256
    while True:
        p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
        q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xFF
        if q & 0x80: q ^= 0x09
        x = q ^ ((q << 1 | q >> 7) & 0xFF) ^ ((q << 2 | q >> 6) & 0xFF) ^ ((q << 3 | q >> 5) & 0xFF) ^ ((q << 4 | q >> 4) & 0xFF)
        box[p] = x ^ 0x63
        if p == 1: break
    box[0] = 0x63
    return box
SBOX = _sbox()
def xt(a): return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1

def expand(key):
    assert len(key) == 32
    w = [list(key[i:i + 4]) for i in range(0, 32, 4)]
    rc = 1
    for i in range(8, 60):
        t = list(w[i - 1])
        if i % 8 == 0:
            t = [SBOX[t[1]] ^ rc, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]]; rc = xt(rc)
        elif i % 8 == 4:
            t = [SBOX[b] for b in t]
        w.append([a ^ b for a, b in zip(w[i - 8], t)])
    return [sum(w[r * 4:r * 4 + 4], []) for r in range(15)]

def block(rk, b):
    s = [x ^ k for x, k in zip(b, rk[0])]
    for r in range(1, 15):
        s = [SBOX[x] for x in s]
        s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]  # ShiftRows, column-major state
        if r < 14:
            m = []
            for c in range(4):
                a = s[4 * c:4 * c + 4]
                m += [xt(a[0]) ^ xt(a[1]) ^ a[1] ^ a[2] ^ a[3], a[0] ^ xt(a[1]) ^ xt(a[2]) ^ a[2] ^ a[3],
                      a[0] ^ a[1] ^ xt(a[2]) ^ xt(a[3]) ^ a[3], xt(a[0]) ^ a[0] ^ a[1] ^ a[2] ^ xt(a[3])]
            s = m
        s = [x ^ k for x, k in zip(s, rk[r])]
    return bytes(s)

def gmul(x, y):
    z = 0
    for i in range(127, -1, -1):
        if (x >> i) & 1: z ^= y
        y = (y >> 1) ^ (0xE1 << 120 if y & 1 else 0)
    return z

def gcm(key, iv, plain, aad):
    assert len(iv) == 12
    rk = expand(key)
    h = int.from_bytes(block(rk, bytes(16)), "big")
    out = bytearray()
    for i in range(0, len(plain), 16):
        ks = block(rk, iv + struct.pack(">I", 2 + i // 16))
        out += bytes(a ^ b for a, b in zip(plain[i:i + 16], ks))
    def pad(b): return b + bytes(-len(b) % 16)
    data = pad(aad) + pad(bytes(out)) + struct.pack(">QQ", len(aad) * 8, len(out) * 8)
    y = 0
    for i in range(0, len(data), 16): y = gmul(y ^ int.from_bytes(data[i:i + 16], "big"), h)
    tag = bytes(a ^ b for a, b in zip(y.to_bytes(16, "big"), block(rk, iv + b"\x00\x00\x00\x01")))
    return bytes(out) + tag

# ---- the spec ----
def mac(key, msg): return hmac.new(key, msg, hashlib.sha256).digest()
def hkdf(ikm, info, n=32):  # RFC 5869, no salt, one block
    return mac(mac(bytes(32), ikm), info + b"\x01")[:n]

SIZES = [163, 233, 512, 1024, 2048, 4096, 8192, 16018]
W = 1 << 20

def field(b): return struct.pack(">H", len(b)) + b

def body_of(sender, name, text, fourth=None, fifth=None, sixth=None):
    """The fields. With a 5th field and nothing for the 4th, the 4th is there and empty."""
    b = field(sender.encode()) + field(name.encode()) + field(text)
    if fourth is None and fifth is not None: fourth = b""
    for f in (fourth, fifth, sixth):
        if f is not None: b += field(f)
    return b

def mark(signed):
    """What a reply or reaction names a message by, next to its packet id: the first 8 bytes of SHA-256 of the signed
    message, the digest its signature is over. It covers id, time and body, not the IV or the ciphertext."""
    return hashlib.sha256(signed).digest()[:8]

def content_key(wire_ts, cipher):
    """What tells PACKETS with one id apart (spec "The same packet"); only to show that a re-sealed copy is another packet."""
    return hashlib.sha256(struct.pack(">q", wire_ts) + cipher).digest()[:8]

# The spec's list of reactions, in its order; the heart with its variation selector.
LISTED = ["\U0001F44D", "\u2764\uFE0F", "\U0001F602", "\U0001F62E", "\U0001F64F"]

class Epoch:
    def __init__(self, secret, members):
        self.secret, self.members = secret, members
        self.outer = hkdf(secret, b"darkproof-epoch-outer-v1")
        self.context = mac(self.outer, b"darkproof-sig-context-v1")

    def message_key(self, position, number):
        node = mac(self.secret, b"darkproof-tree-v1" + bytes.fromhex(self.members[position]))
        for bit in range(15, -1, -1): node = mac(node, bytes([(number >> bit) & 1]))
        return mac(node, b"darkproof-message-v1")

    def seal(self, position, number, pid, ts, body, iv, signature):
        """A packet sealed with a message key: (cipher, header ts, what the signature covers, signature and padded body)."""
        header = bytes([position, number >> 8, number & 255])
        size = 18 + 12 + 64 + 16 + 3 + len(body)
        body += bytes(next(s for s in SIZES if s >= size) - size)  # padding, part of the signed body
        signed = b"darkproof-sig-mk-v1" + self.context + bytes.fromhex(pid) + struct.pack(">q", ts) + header + body
        sealed = gcm(self.message_key(position, number), iv, signature + body, f"{pid}|{ts}".encode() + header)
        mask = mac(self.outer, b"darkproof-header-v1" + iv)[:3]
        cipher = iv + bytes(a ^ b for a, b in zip(header, mask)) + sealed
        tmask = int.from_bytes(mac(self.outer, b"darkproof-time-v1" + iv)[:3], "big") & (W - 1)
        wire = ts // W * W - W + ((ts % W) ^ tmask)
        return cipher, wire, signed, signature + body

SECRET = bytes([0x11]) * 32
MEMBERS = ["1111111111111111", "2222222222222222"]
SIG = bytes([0x07]) * 64  # a fixed dummy, as in `kind: message-key`: apps check the digest, not this
E = Epoch(SECRET, MEMBERS)

# The existing vector, rebuilt: member 1's message number 5.
MK = dict(id="e608000000000005", ts=1794000123456, iv=bytes([0x33]) * 12)
MK["cipher"], MK["wire"], MK["signed"], _ = E.seal(1, 5, MK["id"], MK["ts"], body_of(MEMBERS[1], "Bea", b"hello"), MK["iv"], SIG)
TARGET = bytes.fromhex(MK["id"]) + mark(MK["signed"])  # the message the replies and reactions below are about

def check():
    want, inside = {}, False
    for line in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "test-vectors.txt"), encoding="utf-8"):
        line = line.rstrip("\n")
        if line == "kind: message-key": inside = True
        elif inside and not line: break
        elif inside and ": " in line: k, v = line.split(": ", 1); want[k] = v
    assert want["outer"] == E.outer.hex(), "outer key"
    assert want["key_1_5"] == E.message_key(1, 5).hex(), "message key"
    assert want["cipher"] == MK["cipher"].hex(), "cipher"
    assert want["wire_ts"] == str(MK["wire"]), "header time"
    assert want["signed_message"] == MK["signed"].hex(), "signed message"
    print("ok: kind: message-key rebuilt byte for byte")

def about_vector(note, number, pid, ts, text, iv, fourth=None, fifth=None, sixth=None, reads="ordinary"):
    cipher, wire, signed, plain = E.seal(1, number, pid, ts, body_of(MEMBERS[1], "Bea", text, fourth, fifth, sixth), iv, SIG)
    out = [f"# {note}", "kind: about", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}",
           "position: 1", f"number: {number}", f"key: {E.message_key(1, number).hex()}", f"id: {pid}", f"ts: {ts}", f"wire_ts: {wire}",
           f"sender: {MEMBERS[1]}", "name: Bea", f"text_hex: {text.hex()}"]
    if fourth: out.append(f"fourth: {fourth.hex()}")
    if fifth is not None: out.append(f"fifth: {fifth.hex()}")
    if sixth is not None: out.append(f"sixth: {sixth.hex()}")
    out += [f"iv: {iv.hex()}", f"signature: {SIG.hex()}", f"plain: {plain.hex()}", f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}",
            f"mark: {mark(signed).hex()}", f"reads: {reads}"]
    if reads in ("reply", "reaction"): out += [f"about_id: {fifth[1:9].hex()}", f"about_mark: {fifth[9:17].hex()}"]
    return "\n".join(out)

def target_vector(note, text, iv, named):
    """A packet under the id, time, position and number of `kind: message-key`: is it the message the vectors above name?"""
    cipher, wire, signed, _ = E.seal(1, 5, MK["id"], MK["ts"], body_of(MEMBERS[1], "Bea", text), iv, SIG)
    return "\n".join([f"# {note}", "kind: about-target", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}",
                      "position: 1", "number: 5", f"id: {MK['id']}", f"ts: {MK['ts']}", f"wire_ts: {wire}", f"text_hex: {text.hex()}", f"iv: {iv.hex()}",
                      f"signature: {SIG.hex()}", f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}", f"content_key: {content_key(wire, cipher).hex()}",
                      f"mark: {mark(signed).hex()}", f"named: {'yes' if named else 'no'}"])

def reactions_vector(note, events, counts):
    out = [f"# {note}", "kind: reactions", f"target: {T}"]
    for i, (member, ts, pid, key, target, text) in enumerate(events, 1):
        out.append(f"r{i}: {member} {ts} {pid} {key} {target} {text.encode().hex() or '-'}")
    out.append("counts: " + (" ".join(f"{t.encode().hex()}={n}" for t, n in counts) or "-"))
    return "\n".join(out)

def split_ids(text):
    """A reader that doesn't know journey notes: the first 200 tokens that are 16 lowercase hex characters."""
    def is_id(t): return len(t) == 16 and all(c in b"0123456789abcdef" for c in t)
    return [t.decode() for t in text.split(b" ") if is_id(t)][:200]

def note_of(t):
    """`h`, 1 or 2 digits, one lowercase letter; at most 16 hops. (hops, link) or None; link "?" for a letter not in bwpri."""
    if len(t) not in (3, 4) or t[:1] != b"h" or not (97 <= t[-1] <= 122): return None
    digits = t[1:-1]
    if not all(48 <= d <= 57 for d in digits): return None
    hops = int(digits)
    if hops > 16: return None
    return hops, (chr(t[-1]) if chr(t[-1]) in "bwpri" else "?")

def split_entries(text):
    """A reader that knows them: the same ids, each with the note in the token right after it, if that token is one."""
    def is_id(t): return len(t) == 16 and all(c in b"0123456789abcdef" for c in t)
    out, after_id = [], False
    for t in [t for t in text.split(b" ") if t]:
        if is_id(t):
            if len(out) == 200: break
            out.append([t.decode(), None]); after_id = True
        else:
            if after_id and note_of(t): out[-1][1] = note_of(t)
            after_id = False
    return out

def receipt_vector(note, text):
    text = text.encode()
    entries = " ".join(i if n is None else f"{i}/{n[0]}/{n[1]}" for i, n in split_entries(text))
    assert [i for i, _ in split_entries(text)] == split_ids(text)  # notes change nothing for a reader that doesn't know them
    return "\n".join([f"# {note}", "kind: receipt-notes", f"text: {text.decode()}", f"ids: {' '.join(split_ids(text)) or '-'}", f"entries: {entries or '-'}"])

def written_note(ttl, link):
    """What a reader's phone writes for a first copy that came with `ttl` hops left: 16 minus the ttl (a ttl above 16 counts as 16), not below 0."""
    return f"h{max(0, 16 - min(ttl, 16))}{link}"

# ---- Disappearing messages (spec/FORMAT.md, "Disappearing messages") ----

def timer_read(field):
    """What a reader makes of a 5th field in a new key: seconds, or None if it is no timer field."""
    if len(field) != 5 or field[0] != 0x03: return None
    n = int.from_bytes(field[1:], "big")
    if n == 0 or n > 604800: return 0
    return max(n, 300)

def timer_field(seconds): return b"\x03" + struct.pack(">I", seconds)

def timer_label(seconds):
    for size, unit in ((604800, "week"), (86400, "day"), (3600, "hour")):
        if seconds % size == 0: return f"{seconds // size} {unit}" + ("" if seconds == size else "s")
    return f"{max(1, seconds // 60)} minute" + ("" if seconds // 60 == 1 else "s")

def timer_notice(seconds):
    if seconds == 0: return "⏱ The timer is off. Messages disappear after 1 to 2 weeks."
    return f"⏱ Messages here now disappear {timer_label(seconds)} after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks."

def epoch_timer(had, maker_admin, kept, field, before):
    """The group's timer on a phone after it takes a new key. had: it held an epoch. maker_admin: the key's maker is an
    admin of the epoch before (for a phone with no epoch: named in the key's own admin list). kept: "write" or "read"
    (numbered below the current epoch: kept only to read with). field: the key's timer or None. before: the timer of
    the epoch before (None: not known); for a key kept only to read with, the group's timer as it is."""
    if not had: return field if maker_admin else None
    if kept == "read": return before  # it is never the current epoch: the group's timer stays
    return field if (maker_admin and field is not None) else before

def deletes(seconds, value_since, now):
    """The timer that deletes at `now`, in ms, or None: only a value that has been the group's for 10 minutes."""
    if seconds is None or seconds == 0 or now - value_since < 600_000: return None
    return seconds * 1000

def goes(seconds, value_since, arrived, now, high_risk):
    """Why a message leaves a phone at `now`, or "no". arrived: when it reached the phone, or first left it if it was
    written there; None: written there and not handed on yet."""
    if arrived is None: return "no"
    if high_risk and arrived < now - 86_400_000: return "high-risk"
    t = deletes(seconds, value_since, now)
    return "timer" if t is not None and arrived + t <= now else "no"

def timer_vectors():
    show = lambda t: "unknown" if t is None else str(t)
    print("\n# ---- Disappearing messages (spec/FORMAT.md, \"Disappearing messages\"). Made by spec/make-reply-vectors.py (argument")
    print("# `timer`), the independent Python implementation of the vectors above. `kind: timer-field`: `field` is the 5th field")
    print("# of a new key, without its 2-byte length; `reads` is the timer in seconds (0: off), or `ignored`.")
    fields = [("Off.", timer_field(0)), ("1 hour.", timer_field(3600)), ("8 hours.", timer_field(28800)), ("1 day.", timer_field(86400)), ("1 week.", timer_field(604800)),
              ("1 second reads as the shortest, 5 minutes.", timer_field(1)), ("299 seconds: the same.", timer_field(299)), ("300 seconds, as written.", timer_field(300)),
              ("301 seconds, as written.", timer_field(301)), ("A week and a second: off.", timer_field(604801)), ("The largest number: off.", timer_field(0xFFFFFFFF)),
              ("4 bytes: ignored.", timer_field(3600)[:4]), ("6 bytes: ignored.", timer_field(3600) + b"\x00"), ("Another type: ignored.", b"\x04" + timer_field(3600)[1:]),
              ("A reply's field (17 bytes) in a new key: ignored.", b"\x01" + TARGET)]
    print("\n\n".join(f"# {note}\nkind: timer-field\nfield: {f.hex()}\nreads: {'ignored' if timer_read(f) is None else timer_read(f)}" for note, f in fields))

    # A whole new key with the field, sealed with the door's rekey key (spec "New keys"), not blurred, padded to the largest size.
    code, pid, ts, iv = "test-group-1", "6a44000000000009", 1794000300000, bytes([0x51]) * 12
    door = hashlib.scrypt(code.encode(), salt=b"darkproof-group-v2", n=32768, r=8, p=1, dklen=32, maxmem=1 << 27)
    rekey_key = hkdf(door, b"darkproof-rekey-v1")
    maker = bytes.fromhex("02ea9227dede645b42e9d369c0759de19b46685357f9d0485b8dc80a535e659acf")
    anna, ben = hashlib.sha256(maker).digest()[:8].hex(), "22eea24ece66aafb"
    box = bytes([0x42]) * 48
    fourth = (b"\x06" + struct.pack(">q", 6) + struct.pack(">q", ts) + maker + bytes([1]) + bytes.fromhex(anna) + bytes(8)
              + struct.pack(">H", 2) + bytes.fromhex(anna) + bytes.fromhex(ben)
              + struct.pack(">H", 2) + b"".join(bytes.fromhex(m) + struct.pack(">H", len(box)) + box for m in (anna, ben)))
    out = []
    for fifth, note in ((timer_field(86400), "A new key that carries the timer \"1 day\" in its 5th field."), (None, "The same new key without a 5th field: the same 4th field, the same size.")):
        body = field(anna.encode()) + field(b"Anna") + field(b"") + field(fourth) + (field(fifth) if fifth is not None else b"")
        body += bytes(16018 - (18 + 12 + 64 + 16) - len(body))
        signed = b"darkproof-sig-v1" + mac(rekey_key, b"darkproof-sig-context-v1") + bytes.fromhex(pid) + struct.pack(">q", ts) + body
        cipher = iv + gcm(rekey_key, iv, SIG + body, f"{pid}|{ts}".encode())
        packet = b"\xb3" + bytes.fromhex(pid) + struct.pack(">q", ts) + bytes([16]) + cipher
        assert len(packet) == 16018
        out.append("\n".join([f"# {note}", "kind: timer-rekey", f"code: {code}", f"rekey_key: {rekey_key.hex()}", f"id: {pid}", f"ts: {ts}", f"sender: {anna}", "name: Anna",
                              "epoch: 6", f"made: {ts}", f"maker: {maker.hex()}", f"admins: {anna}", f"members: {anna} {ben}", f"box: {box.hex()}", f"fourth: {fourth.hex()}",
                              f"fifth: {fifth.hex() if fifth is not None else '-'}", f"iv: {iv.hex()}", f"signature: {SIG.hex()}", f"digest: {hashlib.sha256(signed).hexdigest()}",
                              f"timer: {'none' if fifth is None else timer_read(fifth)}", f"packet: {packet.hex()}"]))
    print("\n# ---- A signed new key with and without the timer (fixed IV, a fixed dummy signature; `digest` is SHA-256 of the signed")
    print("# message). `fourth` is the new key as \"New keys\" lays it out: a reader that knows only four fields reads exactly it")
    print("# from both packets. `timer` is what the 5th field says, `none` without one.")
    print("\n\n".join(out))

    print("\n# ---- Who sets the timer and how epochs inherit it. A phone takes one new key. `had`: it held an epoch before. `maker`:")
    print("# `admin` if the key's maker is an admin of the epoch before it (for a phone that had no epoch: if the key's own admin")
    print("# list names its maker), else `member`. `kept`: `write`, or `read` for a key numbered below the current epoch, kept only")
    print("# to read with. `field`: the key's timer, or `none`. `before`: the timer of the epoch before (`unknown`: not known).")
    print("# `timer`: the group's timer on that phone afterwards.")
    rows = [("A phone that joins learns it from the key that lets it in.", False, True, "write", 28800, None),
            ("Let in by a key without the field: not known yet.", False, True, "write", None, None),
            ("Never told a value by someone the key doesn't name as an admin.", False, False, "write", 3600, None),
            ("An admin sets it.", True, True, "write", 3600, 86400),
            ("An admin's key without the field keeps it.", True, True, "write", None, 86400),
            ("A member's field is ignored.", True, False, "write", 3600, 86400),
            ("A member can't turn it off either.", True, False, "write", 0, 86400),
            ("Only an admin's explicit zero turns it off.", True, True, "write", 0, 86400),
            ("Not known stays not known through a key that inherits.", True, False, "write", None, None),
            ("Not known stays not known through an admin's key without the field.", True, True, "write", None, None),
            ("An admin's key brings it to a phone that didn't know it.", True, True, "write", 604800, None),
            ("A member's key kept only to read with changes nothing.", True, False, "read", 300, 86400)]
    print("\n\n".join(f"# {note}\nkind: timer-epoch\nhad: {'yes' if had else 'no'}\nmaker: {'admin' if adm else 'member'}\nkept: {kept}\nfield: {'none' if f is None else f}\n"
                      f"before: {show(before)}\ntimer: {show(epoch_timer(had, adm, kept, f, before))}" for note, had, adm, kept, f, before in rows))

    # Two admins set different values at once. Both keys are admins' keys with one number, so the one whose epoch has the
    # smaller hash wins (spec "Current epoch"): the hash is SHA-256 of the outer key, HKDF of the secret.
    sa, sb = bytes([0x61]) * 32, bytes([0x62]) * 32
    ha, hb = (hashlib.sha256(hkdf(x, b"darkproof-epoch-outer-v1")).hexdigest() for x in (sa, sb))
    win = "a" if ha < hb else "b"
    ta, tb = (86400, 3600) if win == "a" else (3600, 86400)  # the key that loses carries the shorter timer
    print("\n# ---- Two admins set different timers at once: keys `a` and `b` have one number, both made by admins of the epoch")
    print("# before, whose timer was off. `hash_a`, `hash_b`: SHA-256 of each epoch's outer key; the smaller wins. `order`: the")
    print("# order the keys reach a phone; `member` is a member's next key, one number higher, without a say on the timer.")
    print("# `timer`: the group's timer on that phone afterwards. In the last one the winner comes after the member's key, so it")
    print("# is kept only to read with, and is still its admin's word on the timer.")
    print("\n\n".join(f"kind: timer-rival\nsecret_a: {sa.hex()}\nsecret_b: {sb.hex()}\nhash_a: {ha}\nhash_b: {hb}\ntimer_a: {ta}\ntimer_b: {tb}\nwins: {win}\n"
                      f"order: {order}\ntimer: {max(ta, tb)}"
                      for order in (f"a b", f"b a", f"a b member", f"{'b' if win == 'a' else 'a'} member {win}")))

    print("\n# ---- A key that arrives late, kept only to read with (numbered below the current epoch): epoch N, after a member's")
    print("# key N+1 that is current and inherits the timer. `wins`: the late key is the first of every epoch held at N (the order")
    print("# of \"Current epoch\"). `maker`: `admin` if its maker is an admin of the winning epoch before it (N-1), `not-admin` if")
    print("# not (an admin demoted at N-1 or earlier, or a member). `beaten`: `admin` if its maker is an admin of the epoch it")
    print("# beats (the first with a member list at N), `not-admin` if not (demoted at N itself, by the key it beats). `field`:")
    print("# its timer, or `none`. `before`: the timer of the epoch N that is current at its number (what N+1 inherits without")
    print("# the late key). `timer`: the group's timer afterwards. `lines`: whether it may make the chat's lines (\"joined\",")
    print("# \"made an admin\", the timer's line).")
    late = [("An admin's late key that wins at N: its word on the timer, as if it had come in time.", True, "admin", "admin", 3600, 86400),
            ("The same key without the field: the timer stays.", True, "admin", "admin", None, 86400),
            ("A late key that loses at N sets nothing.", False, "admin", "admin", 3600, 86400),
            ("A late key whose maker isn't an admin of the winning epoch before it (an admin demoted earlier) sets nothing, and freezes nothing.", True, "not-admin", "not-admin", 300, 86400),
            ("Losing and not an admin: nothing.", False, "not-admin", "not-admin", 300, 86400),
            ("An admin's late key that wins can turn it off.", True, "admin", "admin", 0, 86400),
            ("An admin of N-1 whom the key it beats demoted (the review's case): it wins at N, but sets nothing and makes no line.", True, "admin", "not-admin", 300, 86400),
            ("The same demoted admin can't turn it off either.", True, "admin", "not-admin", 0, 86400)]
    def late_speaks(wins, maker, beaten): return wins and maker == "admin" and beaten == "admin"
    def late_timer(wins, maker, beaten, field, before): return field if late_speaks(wins, maker, beaten) and field is not None else before
    print("\n\n".join(f"# {note}\nkind: timer-late\nwins: {'yes' if w else 'no'}\nmaker: {m}\nbeaten: {bt}\nfield: {'none' if f is None else f}\nbefore: {b}\n"
                      f"timer: {late_timer(w, m, bt, f, b)}\nlines: {'yes' if late_speaks(w, m, bt) else 'no'}" for note, w, m, bt, f, b in late))

    t0, hour, day = 1794000000000, 3_600_000, 86_400_000
    print("\n# ---- When a message goes. `seconds`: the group's timer on this phone (`unknown`: not known); `value_since`: since when")
    print("# it has had that value. `arrived`: when the message reached the phone, or, written there, first left it (`none`: not")
    print("# handed on yet). `high_risk`: the phone is in high-risk mode. `goes`: `timer`, `high-risk`, or `no`.")
    due = [("Exactly the timer's length after it arrived: it goes.", 3600, t0 - day, t0, t0 + hour, False),
           ("A millisecond earlier: it stays.", 3600, t0 - day, t0, t0 + hour - 1, False),
           ("The value came 9 minutes 59 seconds ago: it deletes nothing yet.", 3600, t0 + 2 * hour, t0, t0 + 2 * hour + 599_999, False),
           ("10 minutes: it deletes.", 3600, t0 + 2 * hour, t0, t0 + 2 * hour + 600_000, False),
           ("Off: nothing goes by the timer.", 0, t0 - day, t0, t0 + 200 * hour, False),
           ("Not known: the normal rule.", None, t0 - day, t0, t0 + 200 * hour, False),
           ("Written here and not handed on yet: it never goes by the timer.", 3600, t0 - day, None, t0 + 200 * hour, False),
           ("High-risk mode is due before a week's timer.", 604800, t0 - day, t0, t0 + day + 1, True),
           ("High-risk mode counts a day and more: exactly a day stays.", 604800, t0 - day, t0, t0 + day, True),
           ("In high-risk mode a shorter timer is due first.", 3600, t0 - day, t0, t0 + hour, True),
           ("Off, in high-risk mode: the day.", 0, t0 - day, t0, t0 + day + 1, True)]
    print("\n\n".join(f"# {note}\nkind: timer-due\nseconds: {show(sec)}\nvalue_since: {since}\narrived: {'none' if arr is None else arr}\nnow: {now}\n"
                      f"high_risk: {'yes' if hr else 'no'}\ngoes: {goes(sec, since, arr, now, hr)}" for note, sec, since, arr, now, hr in due))

    print("\n# ---- The notice that follows a change: a group message sealed like `kind: about` (member 1's message number 30),")
    print("# with the timer as its 5th field. An app that follows the timer shows nothing of it (`shown: no`); to any other it is")
    print("# an ordinary message with this text.")
    out = []
    for n, seconds, pid, ivb in ((30, 86400, "e60800000000001e", 0x52), (31, 0, "e60800000000001f", 0x53)):
        text = timer_notice(seconds).encode()
        cipher, wire, signed, plain = E.seal(1, n, pid, 1794000400000 + n, body_of(MEMBERS[1], "Bea", text, None, timer_field(seconds)), bytes([ivb]) * 12, SIG)
        out.append("\n".join(["kind: timer-notice", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}", "position: 1", f"number: {n}",
                              f"id: {pid}", f"ts: {1794000400000 + n}", f"wire_ts: {wire}", f"sender: {MEMBERS[1]}", "name: Bea", f"text: {text.decode()}",
                              f"fifth: {timer_field(seconds).hex()}", f"iv: {(bytes([ivb]) * 12).hex()}", f"signature: {SIG.hex()}", f"plain: {plain.hex()}",
                              f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}", f"timer: {seconds}", "shown: no"]))
    print("\n\n".join(out))

LEAVE_TEXT = "\U0001F44B I left the group"
LEAVE_FIELD = b"\x05\x01"

def leave_reads(field):
    """What a reader makes of a group message's 5th field, for the leave note: `leave` only for exactly 05 01."""
    return "leave" if field == LEAVE_FIELD else "ordinary"

def leave_valid(epochs, opened_by, sender, me, checked, rivals=None):
    """Rule L1. epochs: number -> dict(members, read, removes), the epoch that counts at that number (the winner of
    those with a member list). rivals: number -> member lists of rivals that lost at that number: they don't matter.
    The current epoch is the highest. checked: yes, no, or not-yet (the sender's key hasn't come)."""
    if checked == "no" or sender == me: return "no"
    cur, e = max(epochs), epochs.get(opened_by)
    if e is None or e["read"]: return "no"
    for n in range(opened_by, cur + 1):
        x = epochs.get(n)
        if x is None or x["read"] or sender not in x["members"]: return "no"
    if any(x["removes"] == sender for x in epochs.values()): return "no"
    return "not yet" if checked == "not-yet" else "valid"

def leave_act(admin, in_current, removal_held, keys):
    """Rule L4: what an admin's phone does about a member who left."""
    if not admin or not in_current or removal_held: return "nothing"
    return "remove-now" if keys else "retry-hourly"

def leave_vectors():
    print("\n# ---- Leaving the group (spec/FORMAT.md, \"Leaving the group\"). Made by spec/make-reply-vectors.py (argument `leave`),")
    print("# the independent Python implementation of the vectors above. `kind: leave-field`: `field` is a group message's 5th")
    print("# field without its length; `framed` is the empty 4th field and the 5th, each behind its 2-byte length, as they follow")
    print("# the text; `reads`: `leave` (the note) or `ordinary` (the field is ignored, the message is read as text).")
    fields = [("The note: type 0x05, form 0x01.", LEAVE_FIELD), ("Another form (0x02): an ordinary message, read as text.", b"\x05\x02"),
              ("Form 0x00: the same.", b"\x05\x00"), ("One byte: ignored.", b"\x05"), ("Three bytes: ignored.", b"\x05\x01\x00"),
              ("Type 0x06 with the same byte: not a note.", b"\x06\x01")]
    print("\n\n".join(f"# {note}\nkind: leave-field\nfield: {f.hex()}\nframed: {(field(b'') + field(f)).hex()}\nreads: {leave_reads(f)}" for note, f in fields))

    print("\n# ---- A note, sealed like `kind: about` (member 1's message number 32, a fixed dummy signature). `shown: line`: an app")
    print("# that knows the field shows a line, never a bubble, and keeps no copy; `older` is what an app without it shows: the")
    print("# text, as an ordinary message (it reads the 5th field as none).")
    n, pid, ts, ivb = 32, "e608000000000020", 1794000400032, 0x54
    text = LEAVE_TEXT.encode()
    cipher, wire, signed, plain = E.seal(1, n, pid, ts, body_of(MEMBERS[1], "Bea", text, None, LEAVE_FIELD), bytes([ivb]) * 12, SIG)
    print("\n".join(["kind: leave-note", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}", "position: 1", f"number: {n}",
                     f"id: {pid}", f"ts: {ts}", f"wire_ts: {wire}", f"sender: {MEMBERS[1]}", "name: Bea", f"text: {LEAVE_TEXT}", f"text_hex: {text.hex()}",
                     f"fifth: {LEAVE_FIELD.hex()}", f"iv: {(bytes([ivb]) * 12).hex()}", f"signature: {SIG.hex()}", f"plain: {plain.hex()}",
                     f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}", "reads: leave", "shown: line", f"older: {LEAVE_TEXT}"]))

    A, B, C = "1111111111111111", "2222222222222222", "3333333333333333"
    def ep(members, read=False, removes=None): return dict(members=members, read=read, removes=removes)
    print("\n# ---- Which notes count (rule L1). `epoch_N`: an epoch this phone holds, number N, with its member list (comma-separated);")
    print("# `read_N: yes`: kept only to read with (the list is whatever its maker wrote); `removes_N`: a removal that took that")
    print("# member out (it has a new code). `epoch_N` is the winner of its number (of the epochs with a member list); the highest")
    print("# is the current one. `rival_N`: the member list of a rival at N that lost to it (made by a member who isn't an admin):")
    print("# only the winner at each number counts. `opened_by`: the epoch whose key opened the note. `checked`: its signature")
    print("# checked (`yes`), failed (`no`), or can't be checked yet (`not-yet`). `me`: this phone. `valid`: `valid`, `no`, or")
    print("# `not yet` (decided when it checks). Check-in clocks count by the same rule (\"If I go quiet\", Q1).")
    rows = [("Sealed in the current epoch, which lists the sender.", {5: ep([A, B, C])}, 5, C, A, "yes"),
            ("Sealed in epoch 5; the current one is 6; both list the sender.", {5: ep([A, B, C]), 6: ep([A, B, C])}, 5, C, A, "yes"),
            ("Removed at 6, let in again at 7 with the same key: the note sealed in 5, played back now, does nothing.",
             {5: ep([A, B, C]), 6: ep([A, B], removes=C), 7: ep([A, B, C])}, 5, C, A, "yes"),
            ("Left out of 6 by a key, let in again at 7: the same.", {5: ep([A, B, C]), 6: ep([A, B]), 7: ep([A, B, C])}, 5, C, A, "yes"),
            ("Removed at 6: a removed member's note does nothing.", {5: ep([A, B, C]), 6: ep([A, B], removes=C)}, 5, C, A, "yes"),
            ("Opened by an epoch kept only to read with.", {5: ep([A, B, C], read=True), 6: ep([A, B, C])}, 5, C, A, "yes"),
            ("Epoch 6 isn't on this phone: nothing shows that it listed the sender.", {5: ep([A, B, C]), 7: ep([A, B, C])}, 5, C, A, "yes"),
            ("Its signature can't be checked yet.", {5: ep([A, B, C])}, 5, C, A, "not-yet"),
            ("Its signature fails.", {5: ep([A, B, C])}, 5, C, A, "no"),
            ("This phone's own note.", {5: ep([A, B, C])}, 5, A, A, "yes"),
            ("An admin let C in with key 6 while a member's weekly key 6 left C out; the admin's won. That losing rival doesn't matter: C's note counts.",
             {5: ep([A, B]), 6: ep([A, B, C]), 7: ep([A, B, C])}, 6, C, A, "yes", {6: [A, B]}),
            ("The other way round: the winner at 6 leaves C out and a rival that lost lists C. The note does nothing.",
             {5: ep([A, B, C]), 6: ep([A, B])}, 5, C, A, "yes", {6: [A, B, C]})]
    out = []
    for row in rows:
        note, epochs, opened, sender, me, checked = row[:6]
        rivals = row[6] if len(row) > 6 else {}
        lines = [f"# {note}", "kind: leave-valid"]
        for num in sorted(epochs):
            e = epochs[num]
            lines.append(f"epoch_{num}: {','.join(e['members'])}")
            if e["read"]: lines.append(f"read_{num}: yes")
            if e["removes"]: lines.append(f"removes_{num}: {e['removes']}")
            if num in rivals: lines.append(f"rival_{num}: {','.join(rivals[num])}")
        lines += [f"opened_by: {opened}", f"sender: {sender}", f"me: {me}", f"checked: {checked}", f"valid: {leave_valid(epochs, opened, sender, me, checked, rivals)}"]
        out.append("\n".join(lines))
    print("\n\n".join(out))

    print("\n# ---- What a phone does about a valid note (rule L4). `admin`: this phone is an admin of the current epoch and wasn't")
    print("# removed. `in_current`: the member is still in the current epoch. `removal_held`: a removal of them is on this phone")
    print("# already. `keys`: this phone has the encryption keys of every member who stays. `act`: `remove-now` (the normal")
    print("# removal, after 0 to 3 s), `retry-hourly`, or `nothing`.")
    yn = lambda b: "yes" if b else "no"
    print("\n\n".join(f"kind: leave-admin\nadmin: {yn(a)}\nin_current: {yn(i)}\nremoval_held: {yn(r)}\nkeys: {yn(k)}\nact: {leave_act(a, i, r, k)}"
                      for a in (True, False) for i in (True, False) for r in (False, True) for k in (True, False)))

    print("\n# ---- What is shown from the member who left (rule L3). `note_ts`: their note's ts; `sender`, `ts`: a message of the same")
    print("# group; `shown`: whether it is shown, alerted, confirmed and counted.")
    t = 1794000500000
    after = [("A minute before the note.", C, t - 1), ("At the note's own time.", C, t), ("A millisecond after the note.", C, t + 1), ("Someone else, after the note.", B, t + 1)]
    print("\n\n".join(f"# {note}\nkind: leave-after\nleaver: {C}\nnote_ts: {t}\nsender: {s}\nts: {ts_}\nshown: {yn(not (s == C and ts_ > t))}" for note, s, ts_ in after))

QUIET_LEAD = "⏳ Check on me if I don't check in within "
QUIET_STOP = "⏳ Clock stopped. No need to check on me"
Q_MIN, Q_MAX, Q_LATE = 5 * 60_000, 24 * 3_600_000, 12 * 3_600_000

def quiet_field(deadline): return b"\x04" + struct.pack(">q", deadline)

def quiet_raw(field):
    """The deadline a group message's 5th field carries as written, or None if it is no clock field."""
    return struct.unpack(">q", field[1:])[0] if len(field) == 9 and field[0] == 0x04 else None

def quiet_valid(deadline, ts):
    """Rule Q2: 0 (stop), or from 5 minutes to 24 hours after the message's own time, both ends included."""
    return deadline == 0 or ts + Q_MIN <= deadline <= ts + Q_MAX

def quiet_duration(ms):
    minutes = (max(0, ms) + 30_000) // 60_000
    h, m = divmod(minutes, 60)
    n = lambda v, unit: f"{v} {unit}" + ("" if v == 1 else "s")
    if h == 0: return n(m, "minute")
    return n(h, "hour") if m == 0 else n(h, "hour") + " " + n(m, "minute")

def quiet_text(ts, deadline, spot=None):
    if deadline == 0: return QUIET_STOP
    return QUIET_LEAD + quiet_duration(deadline - ts) + (f" · \U0001F4CD {spot}" if spot else "")

def quiet_effect(text, fifth):
    """Rule Q4's sorting of a group message: `clock` (a 0x04 field), `check-in` (an own check-in: text starting with
    U+2705 or U+1F198, and no field that makes it silent), or `none`."""
    if fifth is not None and quiet_raw(fifth) is not None: return "clock"
    if fifth is not None and len(fifth) == 17 and fifth[0] == 0x02: return "none"  # a reaction is never a check-in
    return "check-in" if text.startswith("✅") or text.startswith("\U0001F198") else "none"

def quiet_due(deadline, learned, now, armed=True):
    """Rules Q5 to Q7: `at-deadline` (scheduled), `now` (the phone held it before the deadline), `late`, or `none`."""
    if not armed or deadline == 0: return "none"
    if now < deadline: return "at-deadline"
    if now - deadline > Q_LATE: return "none"
    return "late" if learned > deadline else "now"

def quiet_vectors():
    print("\n# ---- If I go quiet (spec/FORMAT.md, \"If I go quiet\"). Made by spec/make-reply-vectors.py (argument `quiet`), the")
    print("# independent Python implementation of the vectors above. `kind: quiet-field`: `field` is a group message's 5th field")
    print("# without its length; `framed` is the empty 4th field and the 5th, each behind its 2-byte length, as they follow the")
    print("# text; `reads`: `clock` with its `deadline` (ms since 1970 UTC, signed 64-bit big-endian; 0 stops), or `ordinary`")
    print("# (the field is ignored, the message is read as text). `ts`, `valid`: the message's own time, and rule Q2 for it.")
    ts0 = 1793807238631
    fields = [("The brief's example: ts 1793807238631, 1 hour.", quiet_field(ts0 + 3_600_000), ts0), ("Deadline 0: a stop.", quiet_field(0), ts0),
              ("8 bytes: ignored.", quiet_field(ts0 + 3_600_000)[:8], ts0), ("10 bytes: ignored.", quiet_field(ts0 + 3_600_000) + b"\x00", ts0),
              ("Type 0x06 with 8 bytes: not a clock.", b"\x06" + quiet_field(ts0 + 3_600_000)[1:], ts0)]
    out = []
    for note, f, ts in fields:
        d = quiet_raw(f)
        lines = [f"# {note}", "kind: quiet-field", f"field: {f.hex()}", f"framed: {(field(b'') + field(f)).hex()}"]
        lines += [f"reads: clock", f"deadline: {d}", f"ts: {ts}", f"valid: {'yes' if quiet_valid(d, ts) else 'no'}"] if d is not None else ["reads: ordinary"]
        out.append("\n".join(lines))
    print("\n\n".join(out))

    print("\n# ---- A clock message, sealed like `kind: about` (member 1's message number 33, a fixed dummy signature), 1 hour 30")
    print("# minutes. `shown: card`: an app that knows the field shows a card, alerts and confirms it, and keeps a copy; `older` is")
    print("# what an app without it shows: the text, as an ordinary message (it reads the 5th field as none).")
    n, pid, ts, ivb = 33, "e608000000000021", 1794000600033, 0x55
    deadline = ts + 90 * 60_000
    text, fifth = quiet_text(ts, deadline), quiet_field(deadline)
    cipher, wire, signed, plain = E.seal(1, n, pid, ts, body_of(MEMBERS[1], "Bea", text.encode(), None, fifth), bytes([ivb]) * 12, SIG)
    print("\n".join(["kind: quiet-message", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}", "position: 1", f"number: {n}",
                     f"id: {pid}", f"ts: {ts}", f"wire_ts: {wire}", f"sender: {MEMBERS[1]}", "name: Bea", f"text: {text}", f"text_hex: {text.encode().hex()}",
                     f"fifth: {fifth.hex()}", f"deadline: {deadline}", f"iv: {(bytes([ivb]) * 12).hex()}", f"signature: {SIG.hex()}", f"plain: {plain.hex()}",
                     f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}", f"mark: {mark(signed).hex()}", "reads: clock", "shown: card", f"older: {text}"]))

    print("\n# ---- Rule Q2: which deadlines count for a message dated `ts`. `valid: no`: the field is ignored and the message is an")
    print("# ordinary text.")
    t = 1794000000000
    rows = [("4 minutes 59 seconds after.", t + 299_000), ("5 minutes after.", t + Q_MIN), ("24 hours after.", t + Q_MAX), ("24 hours and 1 ms after.", t + Q_MAX + 1),
            ("Before the message.", t - 60_000), ("Negative.", -1), ("0: a stop.", 0), ("The largest number.", 2**63 - 1)]
    print("\n\n".join(f"# {note}\nkind: quiet-valid\nts: {t}\ndeadline: {d}\nvalid: {'yes' if quiet_valid(d, t) else 'no'}" for note, d in rows))

    print("\n# ---- Rule Q3: which clock message of a member counts. `clock_X`: `ts id mark deadline`; `arrival`: the order they")
    print("# reached the phone; `counts`: the one that counts, whatever the order: the greatest ts, then the greater packet id")
    print("# (16 lowercase hex chars), then the greater mark (the first 8 bytes of the signed digest, hex).")
    def pick(clocks, arrival):
        best = None
        for x in arrival:
            c = clocks[x]
            if best is None or (c[0], c[1], c[2]) > (clocks[best][0], clocks[best][1], clocks[best][2]): best = x
        return best
    picks = [("Three clocks, arriving C, A, B, with ts A < B < C: C counts.",
              {"A": (t, "e608000000000101", "00000000000000aa", t + 3_600_000), "B": (t + 60_000, "e608000000000102", "00000000000000bb", t + 7_200_000),
               "C": (t + 120_000, "e608000000000103", "00000000000000cc", t + 1_800_000 + 120_000)}, "C,A,B"),
             ("A stop newer than a clock: the stop counts, the member has no clock.",
              {"A": (t, "e608000000000101", "00000000000000aa", t + 3_600_000), "B": (t + 60_000, "e608000000000102", "00000000000000bb", 0)}, "B,A"),
             ("The same ts: the greater packet id.",
              {"A": (t, "e608000000000201", "00000000000000ff", t + 3_600_000), "B": (t, "e608000000000202", "0000000000000001", t + 7_200_000)}, "B,A"),
             ("The same ts and id: the greater mark.",
              {"A": (t, "e608000000000301", "1000000000000000", t + 3_600_000), "B": (t, "e608000000000301", "0fffffffffffffff", t + 7_200_000)}, "B,A")]
    out = []
    for note, clocks, arrival in picks:
        lines = [f"# {note}", "kind: quiet-pick"] + [f"clock_{x}: {c[0]} {c[1]} {c[2]} {c[3]}" for x, c in sorted(clocks.items())]
        out.append("\n".join(lines + [f"arrival: {arrival}", f"counts: {pick(clocks, arrival.split(','))}"]))
    print("\n\n".join(out))

    print("\n# ---- Rule Q4: what cancels a member's clock dated `clock_ts`. `ts`, `text_hex`, `fifth` (if any): a later group message")
    print("# of the same member; `what`: what it is; `cancels`: whether the clock is no longer armed after it. A stop cancels by")
    print("# rule Q3 (it is the newer clock message); a check-in by rule Q4.")
    clock_ts = 100
    report, react = "\U0001F464 Anna is safe", b"\x02" + TARGET
    rows = [("check-in", "✅ I'm safe", 99, None), ("check-in", "✅ I'm safe", 101, None), ("check-in", "\U0001F198 I need help", 101, None),
            ("check-in", "✅ I'm safe", 100, None), ("report", report, 101, None), ("reaction", "\U0001F44D", 101, react), ("text", "on my way", 101, None),
            ("ask", "❓ Is everyone safe?", 101, None), ("stop", QUIET_STOP, 101, quiet_field(0)), ("stop", QUIET_STOP, 99, quiet_field(0))]
    out = []
    for what, text, ts_, f in rows:
        eff = quiet_effect(text, f)
        cancels = (eff == "check-in" and ts_ > clock_ts) or (eff == "clock" and quiet_raw(f) == 0 and ts_ > clock_ts)
        lines = ["kind: quiet-cancel", f"clock_ts: {clock_ts}", f"what: {what}", f"ts: {ts_}", f"text: {text}", f"text_hex: {text.encode().hex()}"]
        if f is not None: lines.append(f"fifth: {f.hex()}")
        out.append("\n".join(lines + [f"cancels: {'yes' if cancels else 'no'}"]))
    print("\n\n".join(out))

    print("\n# ---- Rules Q5 to Q7: what a phone does about an armed clock. `learned`: when the phone took it; `now`; `armed`: no")
    print("# cancelling check-in or newer clock message reached it. `does`: `at-deadline` (an alert scheduled for the deadline),")
    print("# `now` (an alert at once, worded as on time), `late` (at once, with \"This phone learned of it late.\") or `none`.")
    d = 1794000000000
    rows = [("Learned an hour before the deadline.", d - 3_600_000, d - 3_600_000, True), ("Learned at the deadline.", d, d, True),
            ("Held before, the deadline came while the app was away.", d - 3_600_000, d + 3_600_000, True),
            ("Learned 11 h 59 min after.", d + 43_140_000, d + 43_140_000, True), ("Learned 12 h after.", d + Q_LATE, d + Q_LATE, True),
            ("Learned 12 h and 1 ms after.", d + Q_LATE + 1, d + Q_LATE + 1, True), ("Held before, 12 h and 1 ms after.", d - 60_000, d + Q_LATE + 1, True),
            ("Cancelled.", d + 60_000, d + 60_000, False)]
    print("\n\n".join(f"# {note}\nkind: quiet-late\ndeadline: {d}\nlearned: {l}\nnow: {now}\narmed: {'yes' if a else 'no'}\ndoes: {quiet_due(d, l, now, a)}"
                      for note, l, now, a in rows))

    print("\n# ---- The text (never parsed, except a position read as in a check-in): `deadline - ts` rounded to the nearest minute.")
    t = 1794000000000
    rows = [(30 * 60_000, None), (60 * 60_000, None), (90 * 60_000, None), (180 * 60_000, None), (195 * 60_000, None), (480 * 60_000, None), (1440 * 60_000, None),
            (30 * 60_000 + 29_999, None), (30 * 60_000 + 30_000, None), (60 * 60_000, "45.46420,9.19000 ±12m"), (0, None)]
    out = []
    for ms, spot in rows:
        deadline = 0 if ms == 0 else t + ms
        text = quiet_text(t, deadline, spot)
        lines = ["kind: quiet-text", f"ts: {t}", f"deadline: {deadline}"] + ([f"spot: {spot}"] if spot else []) + [f"text: {text}", f"text_hex: {text.encode().hex()}", f"bytes: {len(text.encode())}"]
        out.append("\n".join(lines))
    print("\n\n".join(out))

# ---- Voice notes (spec/FORMAT.md, "Voice notes") ----
VOICE_TEXT = "\U0001F3A4 Voice note".encode()
VOICE_CONFIGS = {1, 5, 9, 13, 15, 19, 23, 27, 31}  # the 20 ms configs of RFC 6716 table 2
# The codec experiment's field: Apple's encoder, variable bitrate, 8000 bit/s, 48 kHz, 501 packets as it came.
VOICE_FIXTURE, VOICE_FIXTURE_SHA = "apple-8k-vbr-48000.voicefield", "4841ece5e733abfb4a0e9d053e7f6763f0a3d3910f9155804764e841a9b62257"

def voice_accepts(p):
    """One packet: 1 to 255 bytes, mono, 20 ms, one frame (code 0, or code 3 with v = 0 and M = 1, padding as RFC 6716 3.2.5)."""
    if not 1 <= len(p) <= 255: return False
    toc = p[0]
    if toc >> 3 not in VOICE_CONFIGS or toc & 4: return False
    if toc & 3 == 0: return True
    if toc & 3 != 3 or len(p) < 2 or p[1] & 0x80 or p[1] & 0x3F != 1: return False
    i, padding = 2, 0
    if p[1] & 0x40:
        while True:
            if i >= len(p): return False
            n = p[i]; i += 1
            if n == 255: padding += 254
            else: padding += n; break
    return len(p) - i - padding >= 0

def voice_packets(field):
    """The packets of a 4th field (type byte included), or None: parsed exactly to the end, 50 to 500, at most 14000 bytes after the type."""
    if len(field) < 2 or field[0] != 0x02 or len(field) - 1 > 14000: return None
    out, i = [], 1
    while i < len(field):
        n = field[i]
        if n == 0 or i + 1 + n > len(field) or len(out) == 500: return None
        p = field[i + 1:i + 1 + n]
        if not voice_accepts(p): return None
        out.append(p); i += 1 + n
    return out if len(out) >= 50 else None

def voice_frames(ps): return b"".join(bytes([len(p)]) + p for p in ps)

def voice_keep(encoded):
    """What a writer keeps: the first 500 packets, then as many from the start as keep 14000 bytes; None if one is refused or under 50 remain."""
    kept, size = [], 0
    for p in encoded:
        if len(kept) == 500: break
        if not voice_accepts(p): return None
        if size + 1 + len(p) > 14000: break
        kept.append(p); size += 1 + len(p)
    return kept if len(kept) >= 50 else None

def voice_seconds(n): return max(1, (n * 20 + 500) // 1000)

def syn(n, size=2, toc=0x08, second=None):
    """`n` made-up packets of `size` bytes: first byte `toc`, 2nd byte `second` if given, then (i * 31 + j) mod 256."""
    return [bytes(toc if j == 0 else second if j == 1 and second is not None else (i * 31 + j) & 255 for j in range(size)) for i in range(n)]

def voice_field_vector(note, field, text=VOICE_TEXT):
    ps = voice_packets(field)
    reads = "voice" if ps is not None and text == VOICE_TEXT else "text"
    out = [f"# {note}", "kind: voice-field", f"text_hex: {text.hex()}", f"field: {field.hex()}", f"bytes: {len(field) - 1}", f"reads: {reads}"]
    if reads == "voice":
        out += [f"packets: {len(ps)}", f"ms: {len(ps) * 20}", f"seconds: {voice_seconds(len(ps))}", f"label: 0:{voice_seconds(len(ps)):02d}"]
    return "\n".join(out)

def voice_vectors(fixture):
    print("\n# ---- Voice notes (spec/FORMAT.md, \"Voice notes\"). Made by spec/make-reply-vectors.py (argument `voice`), the")
    print("# independent Python implementation of the vectors above. `kind: voice-field`: `field` is a group message's 4th field")
    print("# without its 2-byte length, type byte `02` first; `text_hex` the message's 3rd field; `bytes` how many follow the type")
    print("# byte. `reads: voice`: a reader keeps the note, `packets` long (`ms`, and `seconds` / `label` as shown); `reads: text`:")
    print("# the field is ignored as a whole and the message is its text. The packets are made up (`syn` in the script): only")
    print("# their lengths and first two bytes matter to the rules, not whether a decoder makes sound of them.")
    def good(n=60): return syn(n)
    f = lambda ps: b"\x02" + voice_frames(ps)
    allowed = [0x08, 0x28, 0x48, 0x68, 0x78, 0x98, 0xb8, 0xd8, 0xf8]
    every = []
    for k in range(54):
        t = allowed[k % 9]
        every += syn(1, 4, t) if (k // 9) % 2 == 0 else syn(1, 4, t | 3, 0x01)
    padded = [bytes([0x0b, 0x41, 3, 0x55 + i % 7, 0, 0, 0]) for i in range(60)]
    empty = [bytes([0x0b, 0x41, 2, 0, 0]) for _ in range(60)]
    vs = [
        voice_field_vector("The shortest: 50 packets of 1 byte, the TOC byte alone (a frame of 0 bytes, as RFC 6716 allows).", f(syn(50, 1))),
        voice_field_vector("350 packets of 3 bytes: 7 seconds.", f(syn(350, 3))),
        voice_field_vector("74 packets: 1.48 s, shown as 0:01.", f(syn(74))),
        voice_field_vector("75 packets: 1.5 s, rounded up to 0:02.", f(syn(75))),
        voice_field_vector("500 packets: 10 seconds, the most.", f(syn(500))),
        voice_field_vector("Every allowed first byte: the nine 20 ms configs with code 0 (08 28 48 68 78 98 b8 d8 f8), then with code 3 and one frame (0b 2b ... fb, 2nd byte 01), 54 packets.", f(every)),
        voice_field_vector("Code 3, one frame, padding flag set: 2nd byte 41, padding length 3, a frame of 1 byte, 3 bytes of padding.", f(padded)),
        voice_field_vector("Code 3 whose padding leaves a frame of 0 bytes: accepted.", f(empty)),
        voice_field_vector("Exactly 14000 bytes after the type byte: 56 packets of 249 bytes.", f(syn(56, 249))),
        voice_field_vector("Trailing byte: a length with nothing after it.", f(good()) + b"\x05"),
        voice_field_vector("Trailing length 1 whose packet is cut off.", f(good()) + b"\x01"),
        voice_field_vector("A length of 0.", f(good()) + b"\x00"),
        voice_field_vector("Stereo (TOC 0c).", f(syn(59) + syn(1, toc=0x0c))),
        voice_field_vector("10 ms (config 0, TOC 00).", f(syn(59) + syn(1, toc=0x00))),
        voice_field_vector("40 ms (config 2, TOC 10).", f(syn(59) + syn(1, toc=0x10))),
        voice_field_vector("60 ms (config 3, TOC 18).", f(syn(59) + syn(1, toc=0x18))),
        voice_field_vector("10 ms CELT (config 18, TOC 90).", f(syn(59) + syn(1, toc=0x90))),
        voice_field_vector("Code 1: two frames (TOC 09).", f(syn(59) + syn(1, toc=0x09))),
        voice_field_vector("Code 2: two frames (TOC 0a).", f(syn(59) + syn(1, toc=0x0a))),
        voice_field_vector("Code 3 with two frames (2nd byte 02).", f(syn(59) + syn(1, toc=0x0b, second=0x02))),
        voice_field_vector("Code 3 with v = 1 (2nd byte 81).", f(syn(59) + syn(1, toc=0x0b, second=0x81))),
        voice_field_vector("Code 3 with no 2nd byte.", f(syn(59) + [b"\x0b"])),
        voice_field_vector("Code 3 whose padding is longer than the packet.", f(syn(59) + [bytes([0x0b, 0x41, 30, 1, 2, 3])])),
        voice_field_vector("Code 3 whose padding length never ends.", f(syn(59) + [bytes([0x0b, 0x41, 0xff, 0xff])])),
        voice_field_vector("Code 3 with a padding length byte 255 (254 bytes and more): longer than any 255-byte packet.", f(syn(59) + [bytes([0x0b, 0x41, 0xff, 0x00]) + bytes(251)])),
        voice_field_vector("49 packets: under 1 second.", f(syn(49))),
        voice_field_vector("501 packets: one more than 10 seconds.", f(syn(501))),
        voice_field_vector("14001 bytes after the type byte.", f(syn(55, 249) + syn(1, 250))),
        voice_field_vector("Another type byte (03).", b"\x03" + voice_frames(good())),
        voice_field_vector("Only the type byte.", b"\x02"),
        voice_field_vector("A good field under another text: ignored, so the bubble and the alert never disagree.", f(good()), "Hear this".encode()),
    ]
    print("\n\n".join(vs))

    raw = open(fixture, "rb").read()
    assert hashlib.sha256(raw).hexdigest() == VOICE_FIXTURE_SHA, "not the experiment's file"
    whole, i = [], 1
    while i < len(raw): whole.append(raw[i + 1:i + 1 + raw[i]]); i += 1 + raw[i]
    assert i == len(raw) and raw[0] == 2
    kept = voice_keep(whole)
    trimmed = b"\x02" + voice_frames(kept)
    print("\n# ---- What a writer sends of what its encoder wrote (spec/FORMAT.md, \"Voice notes\", writers). `fixture` is a field from")
    print("# the codec experiment of 2026-11-22: Apple's system Opus encoder, variable bitrate, 8000 bit/s, 48 kHz, ten seconds of")
    print("# speech, every packet as it came (501: start-up delay and the final flush). It is in app/src/test/voice and")
    print("# ios/BlackoutCore/Tests/voice. `fixture_reads`: as it came, readers ignore it; a writer keeps the first 500 packets,")
    print("# which give the field `kept_sha256` (SHA-256 of the field, type byte included), `kept_bytes` after the type byte.")
    print("\n".join(["kind: voice-trim", f"fixture: {VOICE_FIXTURE}", f"fixture_sha256: {VOICE_FIXTURE_SHA}", f"fixture_bytes: {len(raw) - 1}",
                     f"fixture_packets: {len(whole)}", f"fixture_reads: {'voice' if voice_packets(raw) else 'text'}", f"kept_packets: {len(kept)}",
                     f"kept_bytes: {len(trimmed) - 1}", f"kept_sha256: {hashlib.sha256(trimmed).hexdigest()}",
                     f"reads: {'voice' if voice_packets(trimmed) else 'text'}", f"seconds: {voice_seconds(len(kept))}"]))

    print("\n# ---- A voice note that answers a message (spec/FORMAT.md, \"Voice notes\", \"Replies\"): the 4th field is the note, the")
    print("# 5th the reply to the message of `kind: message-key`. Sealed like `kind: about` (member 1's message number 40, a fixed")
    print("# dummy signature). `older`: what an app without voice notes shows, the text.")
    n, pid, ts, ivb = 40, "e608000000000028", 1794000700040, 0x60
    fourth, fifth = f(syn(60, 3)), b"\x01" + TARGET
    cipher, wire, signed, plain = E.seal(1, n, pid, ts, body_of(MEMBERS[1], "Bea", VOICE_TEXT, fourth, fifth), bytes([ivb]) * 12, SIG)
    print("\n".join(["kind: voice-message", f"secret: {SECRET.hex()}", f"member_0: {MEMBERS[0]}", f"member_1: {MEMBERS[1]}", "position: 1", f"number: {n}",
                     f"key: {E.message_key(1, n).hex()}", f"id: {pid}", f"ts: {ts}", f"wire_ts: {wire}", f"sender: {MEMBERS[1]}", "name: Bea",
                     f"text_hex: {VOICE_TEXT.hex()}", f"fourth: {fourth.hex()}", f"fifth: {fifth.hex()}", f"iv: {(bytes([ivb]) * 12).hex()}",
                     f"signature: {SIG.hex()}", f"plain: {plain.hex()}", f"signed_message: {signed.hex()}", f"cipher: {cipher.hex()}",
                     f"mark: {mark(signed).hex()}", "reads: voice", f"packets: {len(voice_packets(fourth))}", f"seconds: {voice_seconds(60)}",
                     f"about_id: {fifth[1:9].hex()}", f"about_mark: {fifth[9:17].hex()}", f"older: {VOICE_TEXT.decode()}"]))

# ---- Finder (spec/FORMAT.md, "Finder") ----
FINDER_TYPE = 0x06
HELP = "\U0001F198"

def finder_reads(field, text, channel, checked):
    """Rule F4: the link id a reader takes, or None."""
    if channel != "group" or checked != "yes" or field is None: return None
    if len(field) != 7 or field[0] != FINDER_TYPE or not text.startswith(HELP): return None
    return field[1:].hex()

def finder_fits(sender, name, text, keyed):
    """Rule F3: whether the note goes in. Without it the packet is header (18), IV (12), message-key header (3, if
    keyed), signature (64), three fields with their lengths, tag (16); the note adds 2 + 2 + 7."""
    without = 18 + 12 + (3 if keyed else 0) + 64 + 16 + 6 + len(sender.encode()) + len(name.encode()) + len(text.encode())
    return not (without <= 163 and without + 11 > 163), without

def finder_bands(trace):
    """Rules F7 to F12, one step a second. Returns (band letters, trend signs)."""
    EDGE = {"N": -82.0, "V": -70.0, "H": -60.0}
    ORDER = "-FNVH"
    def level(s): return "H" if s >= -60 else "V" if s >= -70 else "N" if s >= -82 else "F"
    readings, S, band, held, bands, trends = [], None, None, 0, [], []
    for t, v in enumerate(trace):
        fresh = v is not None and -110 <= v <= -20
        if fresh: readings.append((t, v))
        readings = [(u, d) for u, d in readings if u > t - 10]
        if not readings:
            S, band, held = None, None, 0
            bands.append("-"); trends.append("."); continue
        if fresh:
            r = sorted(d for u, d in readings if u > t - 5)
            n = len(r)
            m = float(r[n // 2]) if n % 2 else (r[n // 2 - 1] + r[n // 2]) / 2
            S = m if S is None else 0.3 * m + 0.7 * S
        held = held + 1 if S >= -60 else 0
        lv = level(S)
        cap = "H" if held >= 3 else "V"
        target = lv if ORDER.index(lv) <= ORDER.index(cap) else cap
        if band is None or ORDER.index(target) > ORDER.index(band): band = target
        elif ORDER.index(lv) < ORDER.index(band) and S < EDGE[band] - 3: band = lv
        bands.append(band)
        a = [d for u, d in readings if u > t - 5]; b = [d for u, d in readings if u <= t - 5]
        if a and b:
            diff = sum(a) / len(a) - sum(b) / len(b)
            trends.append("+" if diff >= 4 else "-" if diff <= -4 else "=")
        else: trends.append(".")
    return bands, trends

def finder_vectors():
    note = bytes([FINDER_TYPE]) + bytes.fromhex("a1b2c3d4e5f6")
    print("# ---- Finder (spec/FORMAT.md, \"Finder\"). Made by spec/make-reply-vectors.py (argument `finder`). `kind: finder-field`:")
    print("# what a reader takes from a group message's 5th field (`field`, hex; `-` for none) with this `text`, sent in `channel`")
    print("# (`group` or `public`), whose signature checked for a member (`checked`: `yes`, `no`, or `not-yet`: the sender's key")
    print("# hasn't come). `reads`: the link id, or `ignored`.")
    rows = [("The note on a \"needs help\" check-in.", note, HELP + " I need help", "group", "yes"),
            ("With needs, battery and position in the text: the same.", note, HELP + " I need help: trapped · \U0001F50B 15% · \U0001F4CD 45.46420,9.19000 ±12m", "group", "yes"),
            ("Any text that starts with the help sign is a \"needs help\" check-in.", note, HELP, "group", "yes"),
            ("One byte short.", note[:6], HELP + " I need help", "group", "yes"),
            ("One byte too many.", note + b"\x00", HELP + " I need help", "group", "yes"),
            ("Another type (0x03 is the timer).", b"\x03" + note[1:], HELP + " I need help", "group", "yes"),
            ("A type this version doesn't know.", b"\x07" + note[1:], HELP + " I need help", "group", "yes"),
            ("On \"I'm safe\": ignored.", note, "✅ I'm safe", "group", "yes"),
            ("On a report about someone else: ignored.", note, "\U0001F464 Grandma needs help", "group", "yes"),
            ("On an ordinary message: ignored.", note, "I'm stuck", "group", "yes"),
            ("On the Public channel: ignored.", note, HELP + " I need help", "public", "yes"),
            ("Its signature failed: ignored.", note, HELP + " I need help", "group", "no"),
            ("Its sender's key hasn't come yet: not taken until it checks.", note, HELP + " I need help", "group", "not-yet")]
    print("\n\n".join(f"# {n}\nkind: finder-field\nfield: {f.hex() if f is not None else '-'}\ntext: {t}\nchannel: {c}\nchecked: {k}\n"
                      f"reads: {finder_reads(f, t, c, k) or 'ignored'}" for n, f, t, c, k in rows))

    print("\n# ---- Radio first (rule F3). `size`: the whole packet without the note, before padding (`keyed`: sealed with its own")
    print("# message key, 3 bytes more). `note`: whether the writer adds it: `no` only when the packet fits 163 bytes without it")
    print("# and not with it (11 bytes more).")
    a = "1111111111111111"
    fits = [("A short check-in that fits a LoRa radio: the note would push it over, so it stays out.", a, "Marco", HELP + " I need help", True),
            ("The same with the epoch's own key: still over with the note.", a, "Marco", HELP + " I need help", False),
            ("A shorter name: it fits with the note too.", a, "Al", HELP + " I need help", False),
            ("Exactly 163 with the note: it goes in.", a, "Alex", HELP + " I need help", False),
            ("With the battery it doesn't fit a radio anyway: the note goes in.", a, "Marco", HELP + " I need help · \U0001F50B 15%", True),
            ("The spec's long example (\"Needs and head count\") with a 14-byte name: the note goes in.", a, "Marco Rossetti",
             HELP + " I need help: injured, water · \U0001F465 3 · \U0001F50B 23% · \U0001F4CD 45.46420,9.19000 ±12m", True)]
    out = []
    for n, snd, name, text, keyed in fits:
        ok, size = finder_fits(snd, name, text, keyed)
        out.append(f"# {n}\nkind: finder-fit\nsender: {snd}\nname: {name}\ntext: {text}\nkeyed: {'yes' if keyed else 'no'}\nsize: {size}\nnote: {'yes' if ok else 'no'}")
    print("\n\n".join(out))

    print("\n# ---- From signal readings to a band (rules F7 to F12). `rssi`: one entry a second from second 0, a reading in dBm or")
    print("# `-` for none. `bands`: what the screen shows each second: `-` Not in reach, `F` Far, `N` Near, `V` Very close,")
    print("# `H` Here. `trends`: `+` Signal getting stronger, `-` Signal getting weaker, `=` Signal steady, `.` no trend yet.")
    def ramp(a, b, step): return list(range(a, b + (1 if step > 0 else -1), step))
    traces = [
        ("Walking up to the phone: Far, Near, Very close, then Here once it has held 3 seconds.", ramp(-96, -50, 2) + [-50] * 4),
        ("One strong reading among weak ones: the median drops it, never Here.", [-82, -83, -81, -82, -50, -82, -83, -81, -82, -82]),
        ("Two seconds at Here's edge (seconds 12 and 13) are not enough; later the third second is.", [-66] * 5 + [-59] * 7 + [-70] * 5 + [-52] * 8),
        ("Leaving a band needs 3 dB below its edge: a dip to -72 stays Very close, -78 drops to Near.", [-66] * 5 + [-72] * 6 + [-78] * 8),
        ("Readings stop: the band stays while the last one is under 10 seconds old, then Not in reach, and it starts over.", [-65] * 4 + [None] * 11 + [-90, -90]),
        ("127, 0, -115 and -10 are no readings: Not in reach until a real one comes.", [127, 0, -115, -10, -88, 127, -88, 0, -87, -88]),
        ("Walking away: the trend says weaker.", ramp(-55, -95, -3) + [-95] * 3),
        ("Standing still: steady.", [-74, -75, -73, -74, -76, -74, -75, -73, -74, -75, -74, -74]),
    ]
    out = []
    for n, tr in traces:
        b, t = finder_bands(tr)
        out.append(f"# {n}\nkind: finder-bands\nrssi: {' '.join('-' if v is None else str(v) for v in tr)}\nbands: {' '.join(b)}\ntrends: {' '.join(t)}")
    print("\n\n".join(out))

if __name__ == "__main__":
    if "--check" in sys.argv: check(); sys.exit(0)
    if "voice" in sys.argv:
        rest = sys.argv[sys.argv.index("voice") + 1:]
        here = os.path.dirname(os.path.abspath(__file__))
        voice_vectors(rest[0] if rest else os.path.join(here, "..", "ios", "BlackoutCore", "Tests", "voice", VOICE_FIXTURE)); sys.exit(0)
    if "timer" in sys.argv: timer_vectors(); sys.exit(0)
    if "leave" in sys.argv: leave_vectors(); sys.exit(0)
    if "quiet" in sys.argv: quiet_vectors(); sys.exit(0)
    if "finder" in sys.argv: finder_vectors(); sys.exit(0)
    T = TARGET[:8].hex() + ":" + TARGET[8:].hex()
    reply, reaction = b"\x01" + TARGET, b"\x02" + TARGET
    jpeg = b"\x01" + bytes.fromhex("ffd8ffe000104a464946ffd9")  # the 4th field of a photo: 0x01, then (here very few) JPEG bytes
    print("\n# ---- Replies and reactions (spec/FORMAT.md, \"Replies and reactions\"). Made by spec/make-reply-vectors.py, an independent")
    print("# Python implementation (standard library only) that first rebuilds `kind: message-key` byte for byte. Each `kind: about`")
    print("# is a group message sealed with its own key like that vector (same secret and members, member 1, a fixed dummy")
    print("# signature); `plain` is the signature and the body with its padding; `mark` is the first 8 bytes of SHA-256 of")
    print("# `signed_message`, what a reply to THIS message would name it by. `fifth` is the 5th field as sealed; `reads` is what a")
    print("# reader makes of it: `reply` or `reaction` (then `about_id` and `about_mark` are the message it names: here the one in")
    print("# `kind: message-key`), `ordinary` (the field is ignored), or `nothing` (type 0x02 whose text is no listed reaction:")
    print("# not a reaction and not shown at all). A reader that doesn't know the 5th field reads sender, name, text and the")
    print("# photo exactly as sealed.")
    vs = [
        about_vector("A reply. No photo: the 4th field is there and empty.", 6, "e608000000000006", 1794000200000, "On my way".encode(), bytes([0x41]) * 12, fifth=reply, reads="reply"),
        about_vector("A reaction: the text is a listed reaction (thumbs up).", 7, "e608000000000007", 1794000201000, LISTED[0].encode(), bytes([0x42]) * 12, fifth=reaction, reads="reaction"),
        about_vector("A reaction taken back: the text is empty.", 8, "e608000000000008", 1794000202000, b"", bytes([0x43]) * 12, fifth=reaction, reads="reaction"),
        about_vector("A reply with a photo: the 4th field and the 5th together.", 9, "e608000000000009", 1794000203000, "📷 Here".encode(), bytes([0x44]) * 12, fourth=jpeg, fifth=reply, reads="reply"),
        about_vector("A 5th field of a type this version doesn't know (0x03): ignored, an ordinary message.", 10, "e60800000000000a", 1794000204000, b"hello", bytes([0x45]) * 12, fifth=b"\x03" + TARGET, reads="ordinary"),
        about_vector("A 5th field one byte short (16 bytes): ignored.", 11, "e60800000000000b", 1794000205000, b"hello", bytes([0x46]) * 12, fifth=reply[:16], reads="ordinary"),
        about_vector("A 5th field one byte long (18 bytes): ignored.", 12, "e60800000000000c", 1794000206000, b"hello", bytes([0x47]) * 12, fifth=reply + b"\x00", reads="ordinary"),
        about_vector("A 6th field after the 5th: ignored, the reply is read.", 13, "e60800000000000d", 1794000207000, b"hello", bytes([0x48]) * 12, fifth=reply, sixth=b"\x09later", reads="reply"),
        about_vector("Type 0x02 with 17 bytes of text: no reaction, and not shown at all.", 14, "e60800000000000e", 1794000208000, b"seventeen bytes!!", bytes([0x49]) * 12, fifth=reaction, reads="nothing"),
        about_vector("Type 0x02 with text that is not UTF-8 (a lone 0xff): the same.", 15, "e60800000000000f", 1794000209000, b"\xff", bytes([0x4a]) * 12, fifth=reaction, reads="nothing"),
        about_vector("Type 0x02 with four thumbs up (16 bytes): not a listed reaction, not shown at all.", 16, "e608000000000010", 1794000210000, (LISTED[0] * 4).encode(), bytes([0x4b]) * 12, fifth=reaction, reads="nothing"),
        about_vector("No 5th field, only the padding after the text: an ordinary message, as before.", 17, "e608000000000011", 1794000211000, b"hello", bytes([0x4c]) * 12, reads="ordinary"),
        about_vector("The heart as listed, with its variation selector (e2 9d a4 ef b8 8f): a reaction.", 18, "e608000000000012", 1794000212000, LISTED[1].encode(), bytes([0x4d]) * 12, fifth=reaction, reads="reaction"),
        about_vector("The heart without the variation selector (e2 9d a4): not listed, not shown at all.", 19, "e608000000000013", 1794000213000, "\u2764".encode(), bytes([0x4e]) * 12, fifth=reaction, reads="nothing"),
        about_vector("A check mark as a reaction: not listed (it would read as \"I'm safe\" in an app without reactions), not shown at all.", 20, "e608000000000014", 1794000214000, "\u2705".encode(), bytes([0x4f]) * 12, fifth=reaction, reads="nothing"),
        about_vector("A short word as a reaction: not listed, not shown at all. Nobody can put text under a message.", 21, "e608000000000015", 1794000215000, b"ok", bytes([0x50]) * 12, fifth=reaction, reads="nothing"),
    ]
    print("\n\n".join(vs))

    print("\n# ---- What a reply or reaction names (spec/FORMAT.md, \"Replies and reactions\"): each `kind: about-target` is a packet under")
    print("# the id, time, position and number of `kind: message-key`. `mark` is the first 8 bytes of SHA-256 of its")
    print("# `signed_message`; `named: yes` means the replies and reactions above (`about_id`, `about_mark`) are about it.")
    print("# `content_key` (spec \"The same packet\") only shows that these are other packets than the one in `kind: message-key`.")
    ts_ = [
        target_vector("The same message sealed again under another IV: another packet, the same signed message, so still the target.", b"hello", bytes([0x55]) * 12, True),
        target_vector("Another message sent under the same id: not the target.", b"hallo", bytes([0x56]) * 12, False),
    ]
    assert mark(MK["signed"]) == TARGET[8:] and content_key(MK["wire"], MK["cipher"]).hex() not in ts_[0].split("content_key: ")[1][:16]
    print("\n\n".join(ts_))

    A, B, C = "1111111111111111", "2222222222222222", "3333333333333333"
    other = "e6080000000000aa:00000000000000aa"  # another message: not the target
    def ev(member, ts, n, text, target=None): return (member, ts, f"e6080000000001{n:02x}", f"00000000000000{n:02x}", target or T, text)
    up, heart, laugh, wow, thanks = LISTED
    print("\n# ---- Which reactions count (spec/FORMAT.md, \"Reactions\"). `target` is the message looked at, as `<id>:<mark>`. Each `rN`")
    print("# is one checked type 0x02 message in the same group, in the order it arrived: member, ts, its own packet id, its own")
    print("# mark, the message it names, and its text in hex (`-` for none: it takes the reaction back). `counts` is what is shown")
    print("# under the target: text in hex = how many members, in the order of the spec's list; `-` if nothing is shown.")
    rs = [
        reactions_vector("One per member: the latest ts counts, whatever order they arrive in.",
                         [ev(A, 1794000300000, 1, heart), ev(B, 1794000300500, 2, up), ev(A, 1794000301000, 3, up), ev(A, 1794000300400, 4, laugh)], [(up, 2)]),
        reactions_vector("The same ts: the greater packet id counts.",
                         [ev(A, 1794000300000, 0x20, wow), ev(A, 1794000300000, 0x1f, up)], [(wow, 1)]),
        reactions_vector("An empty text takes the member's reaction back; an earlier one doesn't come back.",
                         [ev(A, 1794000300000, 5, up), ev(B, 1794000300000, 6, thanks), ev(A, 1794000301000, 7, "")], [(thanks, 1)]),
        reactions_vector("A reaction to a reaction (r2 names r1) is ignored: it counts for nothing, and r1 is no message to show it under.",
                         [ev(A, 1794000300000, 8, up), ev(B, 1794000301000, 9, heart, target="e608000000000108:0000000000000008")], [(up, 1)]),
        reactions_vector("A reaction to another message doesn't count here.",
                         [ev(A, 1794000300000, 0x0a, up, target=other), ev(B, 1794000300000, 0x0b, laugh)], [(laugh, 1)]),
        reactions_vector("Shown in the order of the list, whoever reacted first.",
                         [ev(A, 1794000300000, 0x0c, thanks), ev(B, 1794000300100, 0x0d, heart), ev(C, 1794000300200, 0x0e, up)], [(up, 1), (heart, 1), (thanks, 1)]),
        reactions_vector("A text that is not listed (a word, a check mark, the heart without its selector) is no reaction: nothing is shown for it, and the member's earlier reaction still counts.",
                         [ev(A, 1794000300000, 0x10, up), ev(A, 1794000301000, 0x11, "ok"), ev(B, 1794000300000, 0x12, "\u2705"), ev(C, 1794000300000, 0x13, "\u2764")], [(up, 1)]),
    ]
    print("\n\n".join(rs))

    i1, i2, i3 = "0123456789abcdef", "89abcdef01234567", "fedcba9876543210"
    print("\n# ---- Journey notes in receipts (spec/FORMAT.md, \"Journey notes\"). `text` is a receipt's text. `ids` is what a reader that")
    print("# doesn't know notes takes from it (the rule of \"Delivery receipts\"); `entries` what a reader that knows them takes:")
    print("# the same ids, each as `id/hops/link` when the token right after it is a note (`?`: a letter this version doesn't")
    print("# know, \"another link\").")
    many = " ".join(f"{n:016x} h{n % 17}{'bwpri'[n % 5]}" for n in range(1, 203))
    ns = [
        receipt_vector("Two ids, each with its note.", f"{i1} h3i {i2} h1b"),
        receipt_vector("Ids with and without a note.", f"{i1} {i2} h16r {i3}"),
        receipt_vector("No notes: a receipt as before.", f"{i1} {i2}"),
        receipt_vector("A letter this version doesn't know: another link. 0 hops and a leading zero are read.", f"{i1} h2z {i2} h0p {i3} h07w"),
        receipt_vector("More than 16 hops, three digits, no digits, no letter, a capital letter: no note.", f"{i1} h17b {i2} h016b {i3} hb {i1} h3 {i2} h3B"),
        receipt_vector("Only the token right after an id counts: not a second note, not one after something else, not one at the start.", f"h1b {i1} h2b h3b {i2} x h4b {i3} h99x h5b"),
        receipt_vector("An id named twice: a reader counts it as it is named first.", f"{i1} h1b {i1} h9i"),
        receipt_vector("More than one space between tokens.", f"{i1}  h3i   {i2}"),
        receipt_vector("202 ids with notes: the first 200 count, with the note after the 200th; nothing after it is read.", many),
    ]
    print("\n\n".join(ns))

    print("\n# ---- What a reader's phone writes (spec/FORMAT.md, \"Journey notes\"): `ttl` is the ttl of the first copy of the message")
    print("# as it arrived, `link` how it arrived; `note` is the token for the receipt. A ttl above 16 counts as 16.")
    js = [f"kind: journey-note\nttl: {ttl}\nlink: {link}\nnote: {written_note(ttl, link)}" for ttl, link in
          [(15, "b"), (16, "w"), (13, "i"), (1, "r"), (0, "p"), (40, "i"), (64, "b")]]
    print("\n\n".join(js))
