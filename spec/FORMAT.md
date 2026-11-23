# DarkProof wire format (v0.6, marker byte 0xB3)

The Android and iPhone apps must produce and accept exactly these bytes.
`test-vectors.txt` holds byte-exact examples; both apps' unit tests check against it.
The app was called Blackout before. Protocol constants such as `blackout-mesh-v1`, `blackout-public`, `blackout-mesh` and `it.blackout.mesh.v1` keep that name on purpose: changing them would break every existing group and device.

## Packet

All integers are big-endian.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | marker | `0xB3`. Anything else is dropped (`0xB2` is v0.4). |
| 1 | 8 | id | Random. Written as 16 lowercase hex chars wherever it is used as text. |
| 9 | 8 | ts | Sender's clock, milliseconds since 1970-01-01 UTC, signed 64-bit. |
| 17 | 1 | ttl | Hops left, 0–64. Packets with ttl > 64 are dropped. |
| 18 | n | cipher | 1–16000 bytes. See below. |

Relays read only id, ts and ttl.

## Encryption

- **Key:** the group's **door** key; messages between members use a random epoch secret instead (see "Doors and
  epochs"). The door key is scrypt (RFC 7914) with N = 32768, r = 8, p = 1, 32-byte output. It needs 32 MiB of memory, so every guess
  of a code costs an attacker that memory and real time, not just a little CPU (on a 2024 laptop, about 60 ms).
  - Password: the group code, NFC-normalized, as UTF-8 bytes.
  - Salt: the ASCII bytes of `darkproof-group-v2`. It is the same for every group: the code must stay something people
    can read out or type, with nothing else to copy. Generated codes have 80 bits, far beyond guessing; apps warn
    that custom codes, especially short ones, can still be guessed.
  - Apps derive each key once and keep it in memory; only one derivation runs at a time.
- **Cipher:** AES-256-GCM with a 12-byte random IV and a 16-byte tag.
  - Associated data: the UTF-8 bytes of `<id hex>|<ts decimal>`, for example `0123456789abcdef|1793807238631`.
  - `cipher` = IV (12) ‖ ciphertext ‖ tag (16).
- **Plaintext:** the 64-byte signature (see "Signatures"), then three text fields in this order, then optional
  fields. Everything after the signature is the **body**.
  1. `sender`: the sender's member id in this group (see "Member ids").
  2. `name`: the display name.
  3. `text`: the message.
  4. Optional: a photo (see "Photos"), a voice note (see "Voice notes") or, in introductions, a public key.
  5. Optional: which earlier message this one is about (see "Replies and reactions").
- **Text field:** a 2-byte length followed by that many bytes of standard UTF-8. Do not use Java's modified UTF-8.
- Readers ignore bytes after the fields they know, which leaves room for future fields.

A packet that fails to decrypt is still stored and forwarded. It just isn't shown.

### Padding

Zero bytes after the last field make every whole packet (header included) one of these sizes: **163, 233, 512, 1024, 2048,
4096, 8192 or 16018 bytes**, the smallest that fits. 163 and 233 are the MeshCore and Meshtastic limits, so a packet that fit
a radio before still does. The padding is part of the body, so it is encrypted and signed; it reads as an empty 4th
and 5th field or is ignored. New keys are always padded to 16018, whatever their real length, because it would count the group's members.
Anyone watching still sees how many packets there are.

### Times

So that someone who stores packets can't read when each was written, `ts` in the clear header is blurred. With W = 2^20 ms
(about 17 minutes) and mask = the first 3 bytes of HMAC-SHA256(key, the ASCII bytes of `darkproof-time-v1` ‖ the 12-byte IV)
as a big-endian number, low 20 bits only:

    header ts = floor(ts / W) * W - W + ((ts mod W) XOR mask)

The header is therefore up to 35 minutes before the real time and never after it, so forwarding rules still work. Readers undo
it with the key; associated data, the signature and the time shown all use the real `ts`. A packet that wasn't blurred (the
test vectors) opens too. A relay still sees when a packet reaches it.

## Member ids

Each phone has a separate key in each group (the Public channel counts as one), so nobody can tell that two groups
have the same person in them, or match a person to a phone they see over Bluetooth or Wi-Fi.

- **Member key:** a P-256 (secp256r1) key pair, made at random the first time the phone uses the group and kept in the
  phone's secure storage (Android Keystore, iPhone Keychain). The private key never leaves the phone. Reinstalling the
  app or resetting the phone makes new member keys, which the others see as a new key (see "Person emoji").
- **Public key:** the 33-byte compressed point (SEC 1: `02` or `03`, then x).
- **Member id:** the first 8 bytes of SHA-256(public key) as 16 lowercase hex chars. It goes in the `sender` field
  of the member's messages, receipts and introductions in that group.
- Posts from the Wi-Fi board (Android) carry `web-` and the host phone's Public member id, and are signed with the
  host phone's Public member key: readers know they came through that phone, not who typed them. For forwarding they
  are other people's packets, not the host phone's own: on the relays they wait their turn with the packets it passes
  on, and together they count as one link's share of the packets it keeps for passing on (see "A full queue"). The
  board takes at most 10 posts a minute in all, fewer than the 13 packets a minute a phone passes on to the relays,
  and at most 4 of them from one visitor's address, so one visitor can't use up everyone's.
- Vectors: `kind: member` (public key → member id and emoji).

## Signatures

Every packet a phone writes (messages, receipts and introductions) is signed with the writer's member key in that
group, inside the encryption, so nobody else in the group can write under another member's id.

- **Signature:** ECDSA over P-256 with SHA-256, as r ‖ s (32 bytes each, big-endian), the first 64 bytes of the
  plaintext. ECDSA signatures differ every time; only checking them is reproducible.
- **Signed message:** the ASCII bytes of `darkproof-sig-v1`, then the **context** (32 bytes), the packet id (8 bytes),
  `ts` (8 bytes, big-endian) and the body. Context = HMAC-SHA256(the key the packet is sealed with, the ASCII bytes of
  `darkproof-sig-context-v1`). A signed body can't be moved to another group, kind of packet, id or time.
  A packet sealed with a message key signs a different message, with the epoch's outer key for the context (see
  "Message keys").
- **Checking:** the reader takes the public key of the `sender` member id (`web-` removed) from that group's
  introductions or the keys it saved, then:
  - the signature checks → the message is that member's;
  - it doesn't → the packet is dropped like one it can't open: stored and passed on, never shown;
  - no public key known yet → shown with "not verified yet", and checked again when the introduction arrives.
- **The sender field:** a message or receipt whose `sender` is not a member id (16 lowercase hex chars), or `web-` and
  one, is dropped like a packet the reader can't open: no key could ever be known for it. Each app marks the user's own
  messages with an id of its own that is never sent; a received `sender` equal to that id is dropped the same way,
  whatever its shape. Only the phone's own member id is shown as its own, and that packet's signature is then checked
  with the phone's own key. So nothing received can pass for written on this phone.
- **Waiting for a key:** what a phone keeps to check later, for a message shown as "not verified yet" or for a receipt:
  - It belongs to one packet, not to an id. Several packets can carry one id (see "The same message again"). When
    an introduction arrives, a waiting check marks or removes a message only if it was kept for the very packet that
    message is shown from; a packet that got no row in the chat doesn't wait at all. Packets are told apart
    by their id together with SHA-256 of the signed digest and the signature.
  - `web-` in front of the sender doesn't count: a Wi-Fi board post waits for its host phone's introduction.
  - Each group and the Public channel have room of their own, 1,000 packets each. When a channel's room is full its
    oldest goes, and what waited for a message goes when that message leaves the phone. Public channel packets, which
    anyone can write under ids that are never introduced, therefore can't keep a group's messages from being checked.
  - A message that no longer waits stays "not verified yet" until the phone next reads its store (when the app
    starts, at the latest), which checks every stored packet again.
- Phones save the public keys they learn, per group (at most 500 per group, the most recently seen), so messages stay
  checked after the introductions expire. Of people who aren't in the group they keep at most 20 (see "Joining"). An
  introduction that brings nothing new, such as one read again from the store, is not saved again.
- Size: a signature adds 64 bytes. Over MeshCore (163 bytes) a check-in still fits with a short name and no position.

## Introductions

A member's public key travels in an introduction, an ordinary packet in the group's mailbox sealed with the group's
**introduction key**: HKDF-SHA256 with the group key as input key material, no salt, info = the ASCII bytes of
`darkproof-intro-v1`, 32-byte output. Apps that can't open it relay it like any other packet.

- Sealed with the door's introduction key, whatever the epoch.
- Body: `sender` = the member id, `name` = the display name (empty on the Public channel: nothing reads it there, and
  a post carries its sender's name itself), `text` = empty, 4th field = the byte `0x03`, the 33-byte
  compressed signing key, the 65-byte uncompressed encryption key and the 1184-byte ML-KEM-768 key (see "Sealing for one
  member"). The Public channel uses the byte `0x02` and leaves out the ML-KEM key; in a group that is refused. Signed like
  every packet, with the signing key.
- Valid only if SHA-256(signing key) starts with the member id, the encryption key is a valid P-256 point, and the
  signature checks with the signing key.
- A member's encryption keys are replaced only by an introduction with a later `ts`, so an old one played back can't
  bring back a key they have deleted.
- An introduction from someone who isn't in the current epoch is that phone's request to join (see "Joining").
- A phone sends its introduction to a group when it first uses it, then whenever its last
  one there is more than 3 days old: at app start and before it writes. So a member's introduction is always among
  the packets of the last 7 days that new phones receive. To the Public channel it sends one, under the same 3-day
  rule, only before its user writes there (or someone posts through its Wi-Fi board), and at app start only while its
  newest post there is less than 7 days old, so readers can still check that post. A phone whose user never posts on
  the Public channel never announces itself there.
- Vectors: `kind: signed` (a signed message and its introduction, with fixed IVs and a fixed signature). The
  `signed-intro` vector checks sealing and signing; its 4th field is the older 34-byte shape, without the encryption key.

## Sealing for one member (HPKE)

Used to give a new epoch secret to one member only (see "New keys").

- HPKE (RFC 9180), base mode, single shot: KEM DHKEM(P-256, HKDF-SHA256) (`0x0010`), KDF HKDF-SHA256 (`0x0001`),
  AEAD AES-256-GCM (`0x0002`), sequence number 0.
- Sealed box: `enc` (the 65-byte uncompressed ephemeral public key) ‖ ciphertext ‖ 16-byte tag.
- The recipient's key is its member **encryption key**, a P-256 key-agreement key kept apart from the signing key.
  Where the phone allows it (Secure Enclave, Android 12 or later), new member keys are made inside secure hardware and
  the private key never leaves it. Nothing on the wire changes.
- Both apps implement it from their platform's primitives (CryptoKit's own HPKE needs iOS 17); the iPhone tests check
  it against CryptoKit's HPKE in both directions. Vectors: `kind: hpke` (a fixed ephemeral key).
- **Post-quantum:** when the recipient has an ML-KEM-768 key (FIPS 203), the box is the 1088-byte ML-KEM ciphertext,
  then HPKE in PSK mode (`mode_psk`) with the ML-KEM shared secret as the PSK and `darkproof-mlkem768-v1` as its id. Opening
  it needs both the P-256 key and the ML-KEM key. Android uses BouncyCastle, iPhones CryptoKit, so the iPhone app needs iOS 26
  or later. A box without ML-KEM is refused, and a new key isn't made until every member's ML-KEM key is known. Vectors:
  `kind: mlkem`, `kind: hpke-psk`.
- **Changing keys:** a phone makes a new encryption key and ML-KEM key for a group when its current one is 7 days old, and
  introduces itself at once. It keeps a replaced key for 14 days, so new keys sealed to it can still be opened, then
  deletes it. Someone who later takes the phone can open recorded new keys only from about the last three weeks.
  A phone that was removed from the group keeps to this schedule (checked every hour), so its keys go on time too;
  it only stops introducing itself, and its new keys are never sent.

## Doors and epochs

The group code doesn't read messages. It opens the group's **door**, and messages are sealed with a random **epoch
secret** that changes. spec/DESIGN-v0.6-groups.md has the reasoning; this section is the rule.

- **Door:** the 32-byte key scrypt makes from the code (see "Encryption"). Only introductions and new keys travel through
  the door. In a group nothing else is read through it, so the code alone doesn't let anyone write.
- **Epoch secret:** 32 random bytes, made by a member's phone and sealed to each member. A phone keeps only what it derives,
  then forgets the secret: the **outer key** (HKDF-SHA256 of the secret, no salt, info = the ASCII bytes of
  `darkproof-epoch-outer-v1`, 32 bytes), which takes the place of "the group key" everywhere in this document (receipt key,
  mailbox, signature context, headers), and each sender's message keys (see "Message keys").
- **Reading and writing:** a phone reads with every epoch it holds and writes with the current epoch. A phone with no epoch
  can't write; what it tries to send waits until it is let in.
- **Only members write in an epoch:** a message or receipt sealed with an epoch's keys is read only if its `sender` (a
  `web-` in front doesn't count) is in the member list that came with that epoch. For a message sealed with a message
  key this is the rule "Whose number"; for one sealed with the outer key, and for a receipt, the phone looks the
  sender up in the list. Every member holds the outer key, and so does someone removed since: without this, a packet
  under an id nobody was let in with, or under a key someone only introduced through the door, would be shown (as
  "not verified yet", or as checked) with any name. An epoch kept only to read with (see "Accepting a new key")
  decides nothing with its list but still reads by it, and that list is whatever its maker wrote. So the sender also
  has to be in the member list of an epoch the phone holds that isn't kept only to read with. Anything else is dropped
  like a packet whose signature doesn't check: stored and passed on, never shown.
- **Current epoch:** the highest number. For the same number: a removal beats anything else; then the removal whose
  maker comes first in the admin list of the epoch before; then a key made by an admin of the epoch before, ahead of
  one made by a member who isn't (a member picks their key's secret, and so its hash); then the smallest SHA-256 of
  the epoch's outer key (HKDF of the secret, see "Message keys"; the hash the vectors give as `hash_a`, `hash_b`). Two
  keys with the same secret, number and standing: the smallest maker id, then the smallest new code.
  "The epoch before" is as in "Accepting a new key"; a phone still joining goes by the admin list of the key that
  lets it in.
- **One secret, several keys:** an epoch is its secret together with its number, its maker and, for a removal, the new
  code and the person removed. A new key that carries a secret the phone already holds under another of these is a
  separate epoch, judged by the same rules as any other; only the same key read again is ignored. Whoever was given a
  secret can send it on in a key of their own, so the first key to bring a secret must not keep out the others.
- **Keeping:** a phone holds at most 64 epochs. Over that, one kept only to read with (see "Accepting a new key") goes first, the last of them in the order of "Current epoch"; if there is none, the lowest epoch. Of those kept only to read with, a phone holds at most 4 from one maker, dropping the last of that maker's in the same order: a member can make as many as the hourly limits allow, and they must not push out the epochs before the current one. An epoch is deleted 7 days after a newer one replaced it, and with it every epoch of the same number that lost to it. The current one stays, however old. Stored
  packets that nothing can open any more and that are older than 7 days are deleted too, so a group's messages last one
  to two weeks on a phone. A newer epoch replaced it at that epoch's date or when this phone got it, whichever is
  later: the date is whatever its maker wrote, and one long past must not delete the epoch before it at once. A phone
  that was away therefore keeps an epoch up to 7 days after it came back.
- **Door id:** HMAC-SHA256(door key, the ASCII bytes of `darkproof-group-id-v1`), first 16 bytes. A phone keeps the id
  of the first code it had for its member keys and saved public keys, even after the group's code changes.

### New keys

A new key is a packet sealed with the door's **rekey key** (HKDF-SHA256 of the door key, no salt, info = the ASCII bytes
of `darkproof-rekey-v1`, 32-byte output) and signed by its maker. Body: `sender` = the maker's member id, `name`, `text`
empty, 4th field:

| Bytes | Field |
|---|---|
| 1 | `0x06`, or `0x07` for a removal |
| 8 | epoch number, big-endian |
| 8 | when it was made, ms, big-endian |
| 33 | the maker's compressed signing key; the signature must check with it, and its hash must start with `sender` |
| 1 + 8·n | admins: a count, then member ids, most senior first |
| 8 | the member id whose join it answers, or the person a removal removes, else 8 zero bytes |
| 2 + 8·m | members: a count, then everyone in the new epoch |
| 2 + n·(8 + 2 + k) | parts: a count, then for some members: member id, length, box |

- **Several packets:** a new key goes in 1, 3 or 10 packets for at most 10, 30 or 100 members, parts spread evenly, each
  packet with its own id and the whole member list. A phone needs only the packet with its part and ignores the others.
  With k packets, packet i (from 0) holds the parts of the members at places i, i + k, i + 2k, … of the member list, in
  that order.
- A packet whose parts aren't exactly those of one such packet is refused by every phone: a part for someone not in the
  member list, a part missing, or parts out of order. Up to 10 members that means a part for each of them, so a maker
  can't list someone and give them none.
- A packet that lists more than 100 admins or more than 100 members, or carries more than 10 parts, isn't read at all:
  the lists are read before the signature can be checked, and anyone with the code can seal such a packet.
- A box holds the 32-byte epoch secret, followed in a removal by the group's new code in UTF-8, which has to be in the
  generated form (see "Typed codes"). Any other shape is refused.
- HPKE `info` = the ASCII bytes of `darkproof-rekey-v1` ‖ the id of the door it travels through (16 bytes). `aad` =
  packet id (8 bytes) ‖ member id (8 bytes).
- A new epoch is the current number + 1. If any member's encryption key is missing, the phone doesn't make it.
- **The timer:** after the 4th field, a new key's packet may carry a 5th field with the group's timer for
  disappearing messages (see "Disappearing messages"), inside the signed body and before the padding. The table above
  is the whole 4th field, so a phone that doesn't know the 5th takes the key exactly as before. A key split over
  several packets carries the same 5th field in each.

### Accepting a new key

The signature is checked first. Then, with "the epoch before" meaning the held epoch numbered one lower, or the current
epoch if there is none:

- **Refused outright:** a number below 1 or above 2^53, more than 1000 above the current epoch, a date below 0 or more
  than 10 minutes ahead, or more than 100 members.
- **A phone with no epoch** accepts the first one that includes it. If the invite it joined with names admins, the
  maker has to be one of them; if not, the first key it sees has to be trusted (see "Invites"). A removal that doesn't
  include it means its code no longer works, if the invite names admins and the removal's maker is one of them: from
  then on the phone accepts no new key through that code, and it remembers this when it restarts. Otherwise the person
  removed, whom an invite made earlier still names, could let it into a group of their own. With a typed code, or a
  link that names nobody, such a removal is ignored: anyone who has the code can sign one, and it must not be able to
  shut the phone out of the group it is joining.
- **Someone a removal took out** decides nothing afterwards and adds nothing, if that removal won its number. A new
  key of theirs that isn't a removal is refused, whatever its number and whichever door it comes through. The epoch
  before the removal lists them for another week, and a key numbered below the removal, through the old door, would
  otherwise be kept to read with: its secret and date are theirs to choose, so it could outlive the removal, and then
  nothing on the phone says that its maker was removed. A removal of theirs is refused when it is numbered one above
  that removal, and also, through the current door, when it is numbered below it: they may have learnt the new code.
  Numbered below it through the old door, seniority decides (see "Through an old door"). The cost: a key they made
  before they were removed, which reaches a phone only after the removal, isn't taken there, so that phone can't read
  what others wrote with it.
- **Only admins of the epoch before** may let someone in, leave a member out or make a removal. Anyone else's new key has
  exactly the same members as the epoch before, and its maker has to be one of them. A removal needs the epoch before it to
  be held. A removal may not include the person it removes.
- **Several epochs before:** when a phone holds rivals with that number, the first of them (see "Current epoch") gives the
  members, and an admin of any of them counts as an admin, with their best place as their seniority. A rival that lost
  doesn't count once a removal that won its number, the same or a higher one, took its maker out: through an old door
  that person can still send one.
- **The admin list** changes only if an admin of the epoch before made the new key. Otherwise it stays as it was,
  whatever the packet says.
- **At most one per member per hour**, compared by the dates of the keys held, and at most 4 per member in the last hour
  as they arrive on this phone, since a key's date is whatever its maker wrote. Neither applies to admins.
- **Numbered below the current epoch:** kept only to read with, and only from someone who is a member of an epoch this
  phone holds. Unless it is a removal, its member list isn't kept; unless an admin made it, its admins are those of the
  epoch before. Such an epoch decides nothing, neither who is an admin nor how senior they are: "the epoch before" a later
  key is one with a member list, else the current epoch. It isn't taken at all once it is past its week (see
  "Keeping"): a newer epoch replaced it more than 7 days ago, counted like there with its date.
- **Through an old door** (a code the group has moved on from): only a removal with the same number as the removal this
  phone followed out of that door, or a new key numbered below that removal. A removal numbered below it is accepted
  only if its maker comes before the maker of every removal the phone holds with a higher number, in the admin list
  of the epoch before it. It then wins like a rival that beat the removal followed (see "Removals and the code").
  Otherwise an admin who is being removed could send a key and then a removal of their own one number higher, and
  phones that got those first would never take the removal. Without the seniority, someone removed could pick a lower
  number and undo their own removal.
- **A part that doesn't open:** a new key that isn't a removal, through the current door, that lists this phone and
  passes the rules above, but whose part for this phone doesn't open or isn't 32 bytes, when the others may be writing
  with it: it is numbered above the current epoch, or the same and wouldn't lose to it whatever the hashes. The other
  phones can't see that. This phone waits a random 0–3 s and then, like a phone that was away (see "The weekly key, and
  coming back"), makes a new key of its own and shows that it is rejoining; an hour later it makes one more, because
  the first can have the same number as the key it couldn't open and lose to it. It makes neither once a key that
  settles it has arrived: a higher-numbered one, or one of the same number that beats the unopened key whatever the
  hashes. If it made a key in the last hour it waits for its hourly check.
- **Trying a part:** opening a part is a key agreement in the phone's secure hardware. Once 4 parts from one maker
  haven't opened in the last hour, by the phone's clock, it tries no more of that maker's for that hour and treats
  them as parts that don't open. A phone with no epoch can't tell who is in the group: it stops at 16 from all makers
  together.
- Phones read their stored packets again after each new epoch, so a new key refused because an earlier one hadn't
  arrived is accepted when it does. A part is opened once: a phone remembers the parts it has opened (the last 512,
  each named by SHA-256 of the part and of the key's maker, number, date and kind) and keeps that list across
  restarts, saved after the epochs. So a stored new key read again costs no key agreement, and never brings back an
  epoch the phone has deleted.

### Removals and the code

- A removal carries the group's new code in each box. It travels through the door the group had until then. A phone
  ignores a removal whose new code is the code of another group it has saved: followed, it would put this group in
  that group's place on the phone.
- A phone follows a removal's code if it's the newest removal, or a late one that beat the removal it had followed:
  a rival with the same number, or a more senior admin's removal with a lower number (see "Through an old door").
  Epochs numbered above it are then dropped. Door id, member keys and saved messages stay; the invite changes.
- Old doors are kept as long as the epoch that came through them, for rival removals only. Introductions and messages
  through an old door are ignored.
- A code the group has moved on from isn't a second group. On a phone that has the group saved under its new code,
  joining with the old code (an old invite) or writing to it leads to that group, and the old code isn't saved: only
  one set of keys reads and writes a group's epochs.
- The removed phone stops writing to the group and introducing itself, and still deletes its replaced encryption keys
  on time ("Changing keys"). It can be told by mistake by a removal that
  loses; a more senior removal that includes it undoes that: one with the same number, or one with a lower number
  whose maker comes before the maker of the removal that removed it, in the admin list of the epoch before the lower
  one. A phone named by a removal that wins this way from a lower number is removed.
- **What the person removed wrote:** once a phone holds the winning removal, it shows nothing from that member that
  it sees for the first time, whatever date the packet carries. The date is the sender's to write, and the person
  removed still has the keys of the epochs before, which phones read with for another week: dated a minute before
  the removal, anything written in that week would otherwise be shown, confirmed and alerted like a member's. What
  the phone had accepted before stays, unless it is dated after the removal: that is a message it reads from its own
  copy (see "Message keys"), or a message or receipt that was waiting for its sender's public key. So after a
  restart, of that member, a phone shows the messages it has a copy of; their receipts, and messages sealed with the
  outer key, have no copy and no longer count. The cost: a message they really wrote before the removal, which reaches
  a phone only after the removal did, isn't shown there. A phone can't tell it from one written afterwards.
- **Two admins at once:** each removal numbered the same counts unless its maker was removed by a more senior one, or
  it lost and its maker is no longer an admin of the current epoch (a phone can't tell when a late one was made).
  People a counted removal took out who are still in the current epoch are removed by the next admin's phone that
  sees it, after a random 0–3 s unless another did. An admin's next new key also keeps admins added by a rival that
  didn't win.

### Joining

- A phone with the code and no epoch sends its introduction; that is its request.
- An admin's phone that sees a valid introduction, through the current door, from someone not in the current epoch shows
  the request, with that person's emoji. It isn't answered automatically: an admin chooses to let them in, which makes a
  new key that includes them, up to 100 members, or turns them down, which is remembered. While no admin is in reach,
  nobody can join.
- Anyone with the code can introduce as many made-up keys as they like. A phone therefore keeps the keys of at most 20
  people who aren't in the group and are asking: introduced in the last 7 days, not in the current epoch, not turned
  down on this phone. A key it doesn't know yet from someone who is in no member list it holds is not kept while those
  20 places are taken; a request that is waiting is never pushed out by a later one. An admin's phone shows at most
  those 20 and alerts at most once a minute per group.
- Creating a group makes epoch 1 at once, with the creator as its only member and admin. Groups saved before v0.6 count
  as created on each phone.

### The weekly key, and coming back

- A phone makes a new key with the same members when the current epoch is 7 days old plus a random delay of up to 6
  hours, checked at start and hourly. The 7 days count from the epoch's date or from when this phone got it, whichever
  is later, so a key dated in the past isn't due at once.
- Past the 6 hours it does so at once, and shows that it is rejoining until a new key or a message from someone else
  arrives. While it is, a removal it can't follow tells it that the group has a new code only if it came through the
  door the phone has for the group and its maker is an admin of the phone's current epoch whom no removal has taken
  out: anyone who has the code can sign a removal.
- A member who sees a new key made after its current epoch but numbered below it, from a member, through the current
  door, knows that phone is behind. It waits a random 0–3 s and, unless another did and it hasn't made a key in the last
  hour, makes a new key for everyone. A key dated more than 10 minutes ahead doesn't count, and one member's keys
  count once an hour by the phone's clock. "Another did" means the phone's current epoch is no longer the one it had
  when the key came, whatever that key's date: the date is its maker's to write.

## Message keys

Each group message has its own key, which a phone deletes once the message has been checked. Packets arrive late and out of order, so the keys are the leaves of a tree, usable in any order.

- **Trees:** per epoch, one per member. Root = HMAC-SHA256(epoch secret, the ASCII bytes of `darkproof-tree-v1` ‖ the 8-byte
  member id). Each node has two children, HMAC-SHA256(node, one byte 0 or 1), 16 levels deep, so 65,536 messages per
  sender and epoch. A phone derives the roots when it gets an epoch and forgets the secret.
- **Message key:** HMAC-SHA256(leaf, the ASCII bytes of `darkproof-message-v1`).
- **Packet:** `cipher` = IV (12) ‖ header (3, masked) ‖ AES-256-GCM ciphertext ‖ tag. The header is the sender's position in
  the epoch's member list (1 byte) and the message number (2 bytes), XORed with the first 3 bytes of HMAC-SHA256(outer key,
  the ASCII bytes of `darkproof-header-v1` ‖ IV). Associated data: `<id hex>|<ts decimal>` followed by the unmasked header.
  Plaintext as in "Encryption".
- **Signed message:** the ASCII bytes of `darkproof-sig-mk-v1`, then the context of the epoch's outer key (32 bytes, see
  "Signatures"), the packet id (8 bytes), `ts` (8 bytes, big-endian), the unmasked header (3 bytes: position, then number)
  and the body. Every member of an epoch can derive every member's message keys, and the associated data proves only
  that. With the position and number signed, a member can't take a message someone signed and seal it again under that
  person's unused numbers: the copy opens, but its signature no longer checks, so no key is deleted. The tag differs
  from `darkproof-sig-v1` so that a signature can't be moved between a packet sealed with a message key and one sealed
  with the outer key either.
- **Whose number:** a packet opens only if the position in its header is the sender's own place in the member list (a `web-`
  in front of the id doesn't count), so a member can't seal a packet under someone else's number and sign it as themselves.
- **Without a message key:** a sender whose numbers have run out seals with the outer key, as in "Encryption". Such a
  packet is read only from a member of that epoch (see "Only members write in an epoch").
- **Deleting:** a leaf is deleted once the signature has checked, never before, so a member who forges a packet under someone
  else's number can't destroy that number's key. Deleting replaces the node above it with its other branches, so nothing kept
  can derive a deleted leaf. A sender deletes the leaf as soon as it has sealed the message.
- **The same message again:** a phone alerts and sends a receipt only for a message it wasn't showing yet. Messages
  are told apart by their packet id together with the first 8 bytes of the signed digest, without the signature: the
  same signed message sealed again, or carrying the other signature that checks for it, is the same message. A packet
  under an id that is taken (see "The same packet") that brings a message already shown, or that names this phone as
  its sender, is not kept and changes nothing on screen. Any other is a message of its own: it is kept and shown, and
  a receipt names it by its packet id. So whoever sends a message of their own first under someone's id doesn't keep
  the real one off the screen. This holds on the Public channel too.
- **This phone's copy:** the app can no longer re-read a message from its packet, so it keeps a copy sealed with a key that stays
  on the phone. The copy goes when the packet goes or when the epoch is deleted, so history lasts as long as before.
  When the epoch is deleted, the copies sealed under it, their packets (which no longer open) and their messages on
  screen go at once, not at the next read of the store. A copy can be up to about 1 KiB longer than the largest packet
  (it adds the group and epoch ids, lengths, nonce and tag); a phone's store of copies reads back copies up to 1 KiB
  over `maxCipher`, and a phone never seals a packet of its own over `maxCipher`.
- **Alerts:** an alert shows a message's text, so it must not outlive the message. When a phone deletes a message, its
  alert goes too (iPhone: alerts are posted under the message id; Android: while the app runs). An alert also goes
  by itself: on Android a week after the message's date (a minute at least), on iPhone a week after it was posted.
- **What this protects:** someone who records traffic and later takes a phone's keys can't open a message that phone has already
  handled, except through the copies on the phone, which last one to two weeks. A message the phone hasn't received yet can
  still be opened with those keys. As in Signal's groups, a thief who keeps the keys can follow later messages of that
  epoch until the next new key.
- Receipts, introductions and new keys don't use message keys. The Public channel doesn't either.
- Vectors: `kind: message-key` (`signed_message` is the signed message above for that packet).

## Person emoji

Sixteen emoji, shown eight to a line, that two people compare, each on their own phone, to check that a member's key is
really that person's.

- SHA-256 of the ASCII bytes of `darkproof-person-emoji-v1` followed by the public key; its first 12 bytes, read 6 bits
  at a time from the top, are 16 indices into the emoji list of "Group fingerprint".
- 96 bits: someone who wants a key with the same emoji has to make about 2^95 keys.
- When the emoji match, the user marks the person **checked**: the phone saves that public key for the person.
- **New key:** when someone writes under the same name as a checked person (the name key of "Reports about others")
  but with another member id, apps warn that this is a new key: either the person reinstalled DarkProof or reset
  their phone, or someone else is using their name. A name that differs from a checked one only in characters that
  draw nothing, or in a compatibility form of its letters, has the same key and warns too. A checked person saved
  with an older form of the key is matched by making that key again.

## Person code

A member's public key in one group as text, so that two people standing together can check each other by scanning
instead of comparing emoji.

- The ASCII text `DPK1:` followed by the base64url (RFC 4648 section 5: `-` and `_`), without padding, of the member's
  public key in that group: the 33-byte compressed point of "Member ids", exactly the bytes the person emoji are
  computed from. 33 bytes are 44 characters with no bits left over, so a code is always 49 characters and a key has
  exactly one code.
- **Optical only:** a phone shows its own code as a QR code and the other phone scans it. A person code is never put
  in a packet or a message.
- **Reading:** a person code is the text `DPK1:`, then exactly 44 characters of the base64url alphabet that give 33
  bytes whose first is `02` or `03`. Nothing is trimmed; anything else is not a person code.
- **Comparing:** all 33 bytes against the public key the phone holds for that person in that group. Equal: the person
  is marked checked, in person. The phone's own key: it scanned itself. Any other key: not that person's, and nothing
  is marked.
- **How** a person was checked (by emoji, or in person) is kept on the phone next to the checked mark and never sent.
  A mark made before this was kept counts as by emoji. A new key (see "Person emoji") is checked in neither way.
- Example: the key `02ea9227dede645b42e9d369c0759de19b46685357f9d0485b8dc80a535e659acf` has the code
  `DPK1:AuqSJ97eZFtC6dNpwHWd4ZtGaFNX-dBIW43IClNeZZrP`.

## Link ids

The **node id** that phones exchange when they link (Bluetooth LE and Wi-Fi HELLO, the Bonjour instance name, the
Nearby endpoint name, the Multipeer peer name) is a **link id**: 12 random lowercase hex chars, new every time the app
starts, never saved and unrelated to member ids. Someone who sees phones over the air can follow one only until its
app restarts, and can't tell who it belongs to.

- After a restart a phone has a new link id, so other phones see a new neighbour; its old links close (a HELLO with a
  different node id means disconnect, and dead TCP links time out).

## Groups and the Public channel

- **Every DarkProof device relays for every other.** Devices connect to any DarkProof device they find, whatever its group, and forward all packets. A group exists only through its code: only members can decrypt its messages.
- **Public channel:** messages sealed with the code `blackout-public`, which everyone knows. Anyone can read and post them, including people without the app through the Wi-Fi board (Android). Apps show them marked as public.
- **Reading:** a device tries its own group's key first, then the public key, then the keys of the other groups saved
  on it (each derived once, in the background). A packet that opens with none is relayed but not shown. Messages of
  every saved group are kept, confirmed (with that group's receipt key) and alerted, not only the group on screen.

## Invites

An invite link carries a group's name and code so nobody has to type the code:

```
darkproof://join?name=<group name>&code=<group code>&admins=<pins>
```

- Both values are URL-encoded query items. `code` has at least 8 characters. `name` is only a label shown on screen; it is not used for encryption, and a missing name means "Group".
- Apps show the link as a QR code and can send it any way the phone can share a link, such as AirDrop.
- New groups get a random code of 16 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, in four groups of four joined by `-` (80 bits of entropy).
- Anyone with the link can ask to join, exactly like anyone who knows the code.
- **`admins`** names who can let the joining phone in: for each of the group's first 5 admins whose key the inviting phone
  knows, the first 16 bytes of SHA-256 of their 33-byte public key, in lower-case hex, run together. A phone that joined with
  such a link ignores new keys from anyone else while it has no epoch, so someone who knows the code can't pose as the group.
  Once it has an epoch the usual rules apply. An `admins` item that isn't in this form counts as none.
- The admins are those of the invite the user chose to join with, kept when the phone first saves that code. Reading a
  link keeps nothing, and a link for a code the phone already has changes nothing, so a link opened later can't replace
  or push out the admins a pending join trusts.
- A typed code, or a link without `admins`, can't name anyone: that phone trusts the first new key that includes it.
- An admin appointed after the invite was made isn't named in it. A phone joining with that invite waits until a named
  admin lets it in or makes a new key.

### Web links

The same invite as a web address, so it also works for people who don't have the app yet:

```
https://darkproof.app/join#name=<group name>&code=<group code>&admins=<pins>
```

- After `#` come the same items as in the `darkproof://` link, encoded the same way: every byte that is not a letter,
  digit, `-`, `.`, `_` or `~` is written `%XX` (so a space is `%20` and `+` is `%2B`). Both apps write exactly these
  bytes; see `kind: invite` in `test-vectors.txt`.
- The code is in the fragment (after `#`), which browsers never send to a server: darkproof.app never sees it.
- Apps read both forms. They also accept `http://`, `www.darkproof.app`, any case in the scheme, host and path, the
  paths `/join/` and `/join.html`, and a query (such as `?utm_source=…`) before the `#`. A link with the code in the
  query instead of the fragment is not an invite.
- When `name` or `code` appears twice, the first one counts.
- Where DarkProof is installed and the phone has verified darkproof.app (Universal Links on the iPhone, App Links on
  Android), the link opens the app. Elsewhere it opens darkproof.app/join, which reads the fragment in the browser only
  and offers "Open in DarkProof" (the `darkproof://` link, so older apps work too) and the downloads.

### Typed codes

When someone types or pastes a group code to join, apps clean it up first, so a code read aloud or copied by hand still
reaches the same group:

1. If the input (trimmed) is an invite link of either form, its code is used exactly as the link carries it.
2. Otherwise apps remove spaces, tabs, line breaks, `-`, `_`, the no-break space and the dashes U+2010–U+2015 and
   U+2212, and turn ASCII `a`–`z` into upper case. If that leaves exactly 16 characters, all from the generated-code
   alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, the code is those 16 in four groups of four joined by `-`, for example
   `k7qf 2m9x pa4t w8rj` → `K7QF-2M9X-PA4T-W8RJ`.
3. Anything else is a custom code: only leading and trailing white space is removed, and everything else is kept as
   typed (case, spaces, dashes).

- Only new input is cleaned. Saved groups keep the code they were created with, because a custom code that happens to
  look like a generated one would otherwise change group.
- A code is in the generated form when it matches `^[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}$` (80 bits). Apps refuse to
  join with anything else: everyone can download a group's packets from the relays and try guesses on them at leisure,
  with one salt for all groups. A group saved earlier under another code keeps working.
- Vectors: `kind: code-normalize`.

## Group fingerprint

Five emoji that two people read out to each other to check that their phones are in the same group. A fake invite QR
code, or a code typed with a mistake, gives different emoji.

- Take HMAC-SHA256 with the group key (see "Encryption") as the key and the ASCII bytes of `darkproof-fingerprint-v1`
  as the message. Read its first 4 bytes as a big-endian 32-bit number; its 30 highest bits, 6 bits at a time from the
  top, are five indices from 0 to 63.
- Index → emoji and English name, from Matrix's SAS emoji list (matrix-org/matrix-spec,
  `data-definitions/sas-emoji.json`, Apache License 2.0), in its order: 🐶 Dog, 🐱 Cat, 🦁 Lion, 🐎 Horse, 🦄 Unicorn,
  🐷 Pig, 🐘 Elephant, 🐰 Rabbit, 🐼 Panda, 🐓 Rooster, 🐧 Penguin, 🐢 Turtle, 🐟 Fish, 🐙 Octopus, 🦋 Butterfly,
  🌷 Flower, 🌳 Tree, 🌵 Cactus, 🍄 Mushroom, 🌏 Globe, 🌙 Moon, ☁️ Cloud, 🔥 Fire, 🍌 Banana, 🍎 Apple, 🍓 Strawberry,
  🌽 Corn, 🍕 Pizza, 🎂 Cake, ❤️ Heart, 😀 Smiley, 🤖 Robot, 🎩 Hat, 👓 Glasses, 🔧 Spanner, 🎅 Santa, 👍 Thumbs Up,
  ☂️ Umbrella, ⌛ Hourglass, ⏰ Clock, 🎁 Gift, 💡 Light Bulb, 📕 Book, ✏️ Pencil, 📎 Paperclip, ✂️ Scissors, 🔒 Lock,
  🔑 Key, 🔨 Hammer, ☎️ Telephone, 🏁 Flag, 🚂 Train, 🚲 Bicycle, ✈️ Aeroplane, 🚀 Rocket, 🏆 Trophy, ⚽ Ball, 🎸 Guitar,
  🎺 Trumpet, 🔔 Bell, ⚓ Anchor, 🎧 Headphones, 📁 Folder, 📌 Pin. The exact code points (some with U+FE0F) are in
  `kind: fingerprint-list`.
- Apps show the emoji separated by spaces and the names separated by `, `.
- Test values: `test-group-1` → HMAC `7a23a477f07a388a…`, indices 30 34 14 36 29, 😀 🔧 🦋 👍 ❤️ (Smiley, Spanner,
  Butterfly, Thumbs Up, Heart). The Public channel → 58 2 35 23 33, 🎺 🦁 🎅 🍌 👓.
- 30 bits are enough for comparing by voice: someone who wants a fake group with the same emoji has to try about
  a billion codes, each costing a scrypt run. They don't protect a short custom code, which can be guessed directly.
- Nothing is sent: each phone computes the fingerprint from its own key.

## Check-ins

A check-in is an ordinary group message, so apps that don't know check-ins still show it as text:

- `✅ I'm safe` means safe. Any message whose text starts with `✅` counts as "safe".
- `🆘 I need help` means needs help. Any message whose text starts with `🆘` counts as "needs help".

A check-in may end with ` · 🔋 NN%`: the sender's battery level (0 to 100) when they sent it, for example
`✅ I'm safe · 🔋 23%`. Apps read the last `🔋 ` in a check-in, followed by 1 to 3 ASCII digits and `%`; anything
else means no battery level. Apps that don't know it just show the text.

A check-in may also end with ` · 📍 LAT,LON` and optionally ` ±Nm`: where the sender was, in decimal degrees (WGS 84,
5 decimals, `.` as the decimal point, `-` for south and west), and the radius in meters their phone was sure of, for
example `✅ I'm safe · 🔋 23% · 📍 45.46420,9.19000 ±12m`. Apps read the last `📍 ` in a check-in; a latitude outside
-90..90 or a longitude outside -180..180 means no position. A phone adds it only if its user turned on location
sharing.

A person's status is their most recent check-in, and so is their battery level. Their position is the one in their
most recent check-in that carried a position.

A "needs help" check-in may also carry the sender's link id in its 5th field, so that group members nearby can tell
which Bluetooth neighbour is that person's phone (see "Finder").

Check-ins, reports about others and "Are you safe?" requests are group messages. On the Public channel, where anyone
can post, both apps show a text of one of these forms as plain text: no check-in card, no entry in the People list,
no alert and no one-tap answers. A reaction (see "Reactions") is never one of them, whatever its text.

### Needs and head count

A "needs help" check-in may say what is needed, and a check-in of either kind how many people it speaks for:

```
🆘 I need help: injured, water · 👥 3 · 🔋 23% · 📍 45.46420,9.19000 ±12m
✅ I'm safe · 👥 4
```

- **Needs:** `🆘 I need help: ` followed by words from this list, in this order, separated by `, `: `injured`,
  `trapped`, `water`, `food`, `medicine`, `shelter`. Apps show them as Injured, Trapped, Need water, Need food,
  Need medicine, Need shelter. Readers take the part up to the first ` · `, compare words without regard to case and
  skip words they don't know, so newer apps can add needs.
- **Head count:** ` · 👥 N`, N from 1 to 99, after the needs and before the battery level. Readers take the last `👥 `
  followed by 1 or 2 ASCII digits and then a space or the end; anything else means no count.
- A person's needs and head count come from their most recent check-in, like the battery level.
- Apps that don't know them still read the status, battery and position (vectors: `kind: checkin`).
- Size: a group message's packet is 135 bytes plus its name and text when sealed with a message key (header 18, IV 12,
  message-key header 3, signature 64, the 16-character member id and three lengths 22, tag 16), 3 bytes less with the
  epoch's own key. So on MeshCore (packets ≤ 163 bytes) the name and the text together have 28 bytes, and on
  Meshtastic (≤ 233 bytes) 98: `🆘 I need help` with a short name fits both; with a battery level it fits only
  Meshtastic; the first example above (85 bytes of text) fits Meshtastic with a name of up to 13 bytes; the longest
  possible check-in (all six needs, 👥 99, battery, position and accuracy, 128 bytes) fits neither radio and travels on
  the other links only.

## Reports about others

Anyone can check in for someone who has no phone or no signal, for example a grandmother they found at the shelter:

```
👤 Grandma is safe
👤 Grandma Rosa needs help: injured, medicine · 👥 2 · 📍 45.46420,9.19000
```

- `👤 `, the person's name, then ` is safe` or ` needs help`. A "needs help" report may list needs like a check-in
  (`: injured, medicine`); either kind may add ` · 👥 N` and ` · 📍 …` (where that person is). There is no battery level.
- The name is one line, at most 24 characters, without `·`. Readers take the part up to the first ` · `; it ends with
  ` is safe`, or contains a last ` needs help` followed by nothing or `: ` and needs. Anything else is not a report.
- The reporter is the message's sender. The text does not repeat it.
- A report does not start with `✅` or `🆘`, so no app, old or new, takes it for the sender's own check-in. Older apps show
  it as text.
- **Name key:** how apps match names (the People list, "Are you safe?", checked people). Names that draw the same
  have the same key:
  1. the name in NFKC, then in lower case;
  2. without the code points that draw nothing: the control characters U+0000–001F and U+007F–009F that aren't white
     space, and Unicode's Default_Ignorable_Code_Point (zero-width characters, direction marks, variation selectors,
     fillers, tags): U+00AD, U+034F, U+061C, U+115F–1160, U+17B4–17B5, U+180B–180F, U+200B–200F, U+202A–202E,
     U+2060–206F, U+3164, U+FE00–FE0F, U+FEFF, U+FFA0, U+FFF0–FFFB, U+1BCA0–1BCA3, U+1D173–1D17A, U+E0000–E0FFF;
  3. in NFKC again (an accent left next to its letter joins it);
  4. with runs of white space made one space, and none at either end. White space is U+0009–000D, U+0020, U+0085,
     U+00A0, U+1680, U+2000–200A, U+2028, U+2029, U+202F, U+205F, U+3000 and the blank Braille pattern U+2800.

  Look-alike letters of another script are not made equal. Vectors: `kind: name-key`.
- **People list:** reports are kept by name key. The latest report for a name wins (its status, needs and count); its
  position stays until a newer report has one. Reports sent by this phone count too. Apps show such an entry as that person's status "reported by" the sender,
  never as the person's own check-in. When a group member whose current name has the same key has their own check-in
  at least as new as the latest report, the report is not shown.
- Vectors: `kind: vouch`.

## "Are you safe?"

A group message that asks everyone, or one person, to check in:

```
❓ Is everyone safe?
❓ Marco, are you safe?
```

- The name is one line, at most 24 characters, without `·`. A message is a request only if it is exactly one of these
  two forms.
- It is for everyone, or for the members whose name has the same key as in "Reports about others". Apps offer them
  one-tap answers, which are ordinary check-ins (`✅ I'm safe`, `🆘 I need help`).
- A one-tap answer goes to the group that asked. If a removal has given that group a new code since the request
  arrived, it goes to the group under its new code (see "Removals and the code"). If the phone no longer has that
  group, the answer isn't sent, and never goes to the group in use instead.
- Vectors: `kind: ask`.

## Photos

A group message can carry one small photo in a **4th plaintext field**, which older readers skip (see "Encryption":
readers ignore bytes after the third field):

- The field has the same 2-byte length prefix. Its first byte is the type, `0x01` = JPEG, followed by the JPEG bytes.
- At most 14000 JPEG bytes, so the packet stays under the 16000-byte cipher limit. Apps shrink photos to fit
  (a few hundred pixels wide) before sending.
- The 3rd field (`text`) is `📷 Photo`, or `📷 ` followed by a caption, so apps that don't know photos show that.
- Readers ignore a 4th field with another type byte. A reader that doesn't know the 5th field (see "Replies and
  reactions") ignores everything after the 4th.
- Photos go only to the group, never to the Public channel, and are too big for LoRa radios.

The 4th field's first byte in a group message, and what else stands there:

| First byte | In | Meaning |
|---|---|---|
| `0x01` | a group message | a JPEG photo (this section) |
| `0x02` | a group message | a voice note (see "Voice notes") |
| `0x02`, `0x03` | an introduction (its own key) | the member's public keys (see "Introductions") |
| `0x06`, `0x07` | a new key (the rekey key) | the new epoch (see "New keys") |

Introductions and new keys are sealed with keys of their own, so their first bytes never meet a message's.

## Voice notes

A group message can carry one voice note of 1 to 10 seconds in its **4th plaintext field**, instead of a photo, as raw
Opus packets (RFC 6716) with no container:

- **Field:** the type byte `0x02`, then the packets one after the other, each behind a 1-byte length: `len` (1 to 255)
  ‖ `len` bytes. Nothing else: no header, no count, no trailing bytes.
- **Packets:** every packet is mono (TOC bit `s` = 0), 20 ms (TOC config 1, 5, 9, 13, 15, 19, 23, 27 or 31: the 20 ms
  configs of SILK NB/MB/WB, Hybrid SWB/FB and CELT NB/WB/SWB/FB) and one frame: either TOC code `c` = 0, or code 3 with
  exactly one frame (2nd byte: `v` = 0, `M` = 1, padding flag either way; padding as RFC 6716 section 3.2.5, a length
  byte 255 meaning 254 bytes and another length byte; what is left after the padding is the frame and may be 0 bytes).
  So the first byte is one of `08 28 48 68 78 98 b8 d8 f8` or `0b 2b 4b 6b 7b 9b bb db fb`. Constant-bitrate encoders
  (Apple's, libopus) write code 3 for about half of all packets.
- **How many:** 50 to 500 packets (1 to 10 s), at most 14000 bytes after the type byte (the limit of a photo).
- **Text:** the 3rd field is exactly `🎤 Voice note` (UTF-8 `f09f8ea420566f696365206e6f7465`), so apps that don't know
  voice notes, alerts and the chat list show that.
- **Readers** keep the note only if the text is exactly that and the field parses exactly to its end with every rule
  above. Otherwise the field is ignored as a whole, nothing of it is decoded, and the message is its text (apps say
  "Can't play this voice note." under it). A reader never shows a photo and a voice note together.
- **Length shown:** packets × 20 ms, in whole seconds rounded half up, at least 1: max(1, (packets × 20 + 500) / 1000),
  written `0:07`.
- **Decoding:** at 48 kHz, mono. The packets carry no Opus header, so the format fixes the encoder's start-up delay:
  decoders drop the first **312 samples** (6.5 ms), and N packets give N × 960 − 312 samples (500 give 479,688). A
  decoder that needs an identification header (RFC 7845 section 5.1) is given one built from constants:
  `4f707573486561640101380180bb0000000000` (version 1, 1 channel, pre-skip 312, input rate 48000, gain 0, mapping
  family 0); where a codec delay and a seek pre-roll are asked for, 6,500,000 ns and 80,000,000 ns. Opus decoders
  differ slightly (fixed or floating point): two apps agree on the sample count, not on the PCM bytes.
- **Writers** ask their encoder for variable bitrate at 8000 bit/s, 20 ms frames, mono, 48 kHz input (constant bitrate
  below 8000 leaves the speech mode). Ten seconds of audio come out of an encoder as 501 packets (start-up delay and the
  final flush): a writer keeps the first 500, then as many from the start as keep the field within 14000 bytes, so an
  encoder that overshoots makes a shorter note, never an oversized one. Before sending, a writer checks what its encoder
  wrote with the readers' rules and sends nothing a reader would ignore: no packet over 255 bytes, none of another
  shape, never under 50 packets (under one second of recording sends nothing).
- **Where:** only in a group. Never on the Public channel (writers don't add it there and readers ignore it), never in a
  reaction, never over LoRa: even the shortest note (50 packets of 1 byte) is padded to 512 bytes, above the radios'
  163 and 233. It may carry a 5th field (see "The 5th field"): a voice reply has the note in the 4th field and the
  reply in the 5th.
- **Shown** only from a packet that opened with a group's key and whose signature checked for a member of that epoch;
  until then apps show the text, marked "not verified yet" like any message.
- Otherwise it is an ordinary group message: forwarding, the 7 days, the store cap, receipts, the group's timer and
  high-risk mode's day treat it like any other. **This phone's copy** (see "Message keys") keeps the field and reads it
  with the same rules.
- **Size:** at 8000 bit/s with variable bitrate, ten seconds of speech take about 9.7 kB after the type byte (Apple's
  encoder in the vectors: 9,686 bytes for 500 packets); constant bitrate would take 10,500 bytes. With a short name, notes
  whose packets and lengths take up to about 8,000 bytes (about 8 s) are padded to the 8192 class; longer ones to 16018.
- **Not known without real phones:** what an iPhone's own encoder and each Android vendor's encoder write (checked so
  far: Apple's encoder on a Mac and in the iPhone Simulator, libopus, and the Android emulator's software encoder);
  that is why writers check their own output.
- Vectors: `kind: voice-field` (fields read and ignored), `kind: voice-trim` (an Apple-made field of 501 packets, by its
  SHA-256, and what a writer keeps of it), `kind: voice-message` (a voice reply sealed with a message key), made by
  spec/make-reply-vectors.py (argument `voice`).

## The 5th field

The 5th plaintext field comes after the 4th (the photo or voice note, or in a new key the key itself). Where there is nothing for
the 4th it is there with length 0. Padding follows the last field. Its first byte is its type:

| Type | Bytes | In | Meaning |
|---|---|---|---|
| `0x01` | 17 | a group message | a reply (see "Replies and reactions") |
| `0x02` | 17 | a group message | a reaction (see "Replies and reactions") |
| `0x03` | 5 | a new key; a group message | the group's timer; in a message only a notice (see "Disappearing messages") |
| `0x04` | 9 | a group message | the sender's check-in clock: `04` and the deadline (see "If I go quiet") |
| `0x05` | 2 | a group message | the sender left the group: `05 01` (see "Leaving the group") |
| `0x06` | 7 | a "needs help" check-in | the sender's link id, for Finder: `06` and 6 bytes (see "Finder") |

- A reader ignores a 5th field whose type it doesn't know, whose length isn't the one listed, or that stands where
  its type has no meaning (a reply in a new key): the packet is then read as if it had no 5th field. Anything after
  the 5th field is ignored.
- The field is inside the signed body. A phone acts on it only from a packet that opened with a group's key and whose
  signature checked for a member of that epoch, never from one shown as "not verified yet", and never on the Public
  channel, where writers don't add it and readers ignore it.
- An app that knows none of this reads the first four fields exactly as before.

## Replies and reactions

A group message may say which earlier message it is about, in a **5th plaintext field** after the photo field. Apps
that don't know the field skip it (see "Encryption": readers ignore bytes after the fields they know) and show an
ordinary message with the same sender, name, text and photo.

- **The field** has the same 2-byte length prefix as every field. Its content is 17 bytes:

  | Bytes | Field |
  |---|---|
  | 1 | type: `0x01` = reply, `0x02` = reaction |
  | 8 | the target's packet id |
  | 8 | the target's **mark**: the first 8 bytes of SHA-256 of the target's signed message, the digest its signature is over |

- **One exact message:** the signed message is the one of "Signatures" (tag `darkproof-sig-v1`) or, for a message
  sealed with a message key, of "Message keys" (tag `darkproof-sig-mk-v1`): tag, context, packet id, `ts`, for a
  message key the position and number, and the body with its padding. The id and the mark are what phones already
  tell messages apart by (see "The same message again").
- **Why what is signed, not the packet:** the mark covers everything the sender signed and nothing else: not the
  IV, the ciphertext or the header time. A copy of a message that someone sealed again under a new IV is another
  packet (see "The same packet") but still the same message, so it has the same mark on every phone that opened and
  checked it, whichever copy that phone got: a reply or a reaction finds it everywhere. Another message sent under
  the same id has another mark and is not the target. The other signature that checks for a message doesn't change
  its mark either. A phone keeps the mark with every message it shows, its own included (this phone's copy of a
  message holds the digest).
- **What 8 bytes give:** nobody can make a second message with the mark of someone else's short of about 2^64 tries.
  The writer of a message can make two of their own with one id and one mark in about 2^32 tries; to a phone those
  two are one message (see "The same message again"), so it shows whichever came first, and a reply to one reads as
  a reply to the other there.
- **Where it sits:** right after the 4th field. A message without a photo carries a 4th field of length 0 in front of
  it. Padding follows the last field as always.
- **Readers** ignore a 5th field of another length or with another type byte, and anything after the 5th field: the
  message is then an ordinary one. Padding reads as a 5th field of length 0.
- **Only in groups.** On the Public channel writers never add the field, and readers ignore it whatever it holds.
- **The sender's own statement:** the field is inside the signed body, so it can't be added to, changed in or cut out
  of someone's message. A phone shows nothing because of it unless the packet opened with a group key and its
  signature checked for a member of that epoch (see "Only members write in an epoch"). While a message is "not
  verified yet" it is shown without a quote, and a reaction counts for nothing; both follow when the sender's key
  arrives (see "Waiting for a key").
- **The target** is looked up on the reader's own phone, in the same group: a message the phone shows, with that
  packet id and that mark, whose signature checked. A message of another group or of the Public channel is never a
  target, and neither is a reaction: someone who is in two groups must not be able to hang a quote or a reaction
  written in one on a message of the other. Nothing about what the target says is ever taken from the message that
  names it.
- **This phone's copy** of a message (see "Message keys") keeps the field.
- **Size:** the field adds 21 bytes (2 for the empty 4th field, 2 and 17 for the 5th), 19 next to a photo. Sealed with
  a message key, a reply is 156 bytes plus its name and text: over Meshtastic (233 bytes) name and text together
  have 77 bytes, over MeshCore (163 bytes) 7.
- Vectors: `kind: about` (sealed replies and reactions that open to known fields, one with a photo, and 5th fields
  that are ignored), `kind: about-target` (the target sealed again under another IV is still the target; another
  message under its id is not).

### Replies

Type `0x01`.

- A reply is an ordinary group message with its own text and, if it has one, its own photo: it is shown, alerted,
  confirmed and counted like any other.
- It is shown with a quote above its text, made from the reader's own copy of the target: the name of the target's
  sender and the first line of its text that isn't empty (for a photo its caption, or "Photo"), cut to fit. Where
  the target is on screen in the chat, tapping the quote scrolls to it.
- If the phone has no such message (it expired, never arrived, or its signature hasn't checked), the quote says
  "A message this phone doesn't have". If the message is there but the reader hid it or blocked its sender, it is
  not quoted either: "A message hidden on this phone".
- A reply to a message this phone no longer has can't be started: the message is not there to hold.

### Reactions

Type `0x02`.

- **The text is the reaction, from a list:** the message's `text` field is, byte for byte, one of the listed
  reactions, or has no bytes at all, which takes the sender's reaction back. A type `0x02` message with any other
  text is not a reaction and is not shown at all, not as a message either; it changes nothing (the sender's earlier
  reaction still counts). So nobody can put words under someone's message. A reaction carries no photo: writers
  leave the 4th field empty, and readers ignore what is in it.
- **The list**, in its order, with the exact UTF-8 bytes:

  | Reaction | Bytes | Code points |
  |---|---|---|
  | 👍 | `f0 9f 91 8d` | U+1F44D |
  | ❤️ | `e2 9d a4 ef b8 8f` | U+2764 U+FE0F (with the variation selector) |
  | 😂 | `f0 9f 98 82` | U+1F602 |
  | 😮 | `f0 9f 98 ae` | U+1F62E |
  | 🙏 | `f0 9f 99 8f` | U+1F64F |

  Apps offer exactly these. Nothing is trimmed or normalized before comparing: the heart without its variation
  selector (`e2 9d a4`), a listed reaction twice, or one with a skin tone is not on the list.
- **Writers only ever send** a listed reaction, or the empty text to take theirs back.
- **Adding to the list:** no reaction longer than 16 bytes will ever be listed, and none that an app without
  reactions would take for something else. Such an app shows a reaction as an ordinary message, so a text that
  starts with `✅` or `🆘` would be a check-in there (a false "I'm safe", or a call for help), and one in the form of
  "Reports about others" or of "Are you safe?" a report or a request. This is why `✅` is not listed. A phone whose
  list is shorter than the sender's ignores the newer reaction like any other text.
- **Which one counts:** one reaction per member per target. Of the reactions to a target that a phone holds from
  one member (a `web-` in front of the `sender` doesn't count), the one with the latest `ts` counts, the time
  inside the packet; of several with the same `ts`, the one whose packet id is greater as 16 hex chars. A member
  whose counting reaction has no text has none. The result depends only on which reaction packets the phone holds,
  not on the order they came in.
- A writer gives a new reaction a `ts` later than that of its own last reaction to the same target, so the newest
  one counts even if its clock was set back meanwhile.
- **A reaction to a reaction is ignored:** a reaction is never a target, so nothing is shown for it.
- **Never a message:** a reaction is not shown as a chat row, not alerted, not counted as unread, and not confirmed
  with a receipt; a receipt that names one confirms nothing, so it never shows "Delivered" or "Sent". It does not
  make its sender appear in the People list as having checked in or written, whatever its text.
- **Shown** as chips under the target: each listed reaction that a member has, with the number of members whose
  reaction it is, the user's own marked, in the order of the list. Tapping a chip
  makes it the user's own reaction, or takes the user's reaction back if it already is. The user's own reaction
  shows at once. Apps don't show the reactions of someone the user blocked in that group.
- **Someone removed:** a reaction is a message from its sender. One a phone sees for the first time after the
  winning removal of its sender doesn't count, by the rule that stops their messages (see "What the person removed
  wrote").
- **Forwarding and storage** are as for any group message (7 days, the store cap, this phone's copy, "The same
  message again"), with one difference: a phone that can read a reaction doesn't put it on a LoRa radio, where
  airtime is scarce, neither its own nor one it passes on, like a receipt. A phone that can't read it can't tell it
  from any other packet. This is decided for the very packet (see "The same packet"), never for an id alone: a
  reaction or a receipt sent under the id of a message must not keep that message off the radio. A reaction counts
  while its packet is on the phone; when its target is no longer there, it is not shown.
- **Apps that don't know reactions** show each one as a short message from its sender (an empty one for a reaction
  taken back), alert and confirm it. The sender's phone shows nothing for those receipts.
- Vectors: `kind: reactions` (latest wins, the tie by id, taking back, a reaction to a reaction, another target,
  the order shown, texts that aren't listed); in `kind: about`, type `0x02` with the listed heart, the heart without
  its selector, a check mark, a word, 17 bytes and bytes that aren't UTF-8.

## Places

A place is an ordinary **Public channel** message that tells everyone nearby where to find help, so apps that don't
know places (and the Wi-Fi board) still show it as text:

```
💧 Water: tap at the school gym, bring bottles · 📍 45.46420,9.19000 ±12m
```

- It starts with one of `💧 Water`, `🏠 Shelter`, `🔌 Charging` or `🏥 Medical`, followed by nothing, `:` and a note
  (at most 200 characters), or ` · 📍 …`.
- The position, if any, is written and read exactly like a check-in's (see "Check-ins"). It is where the place is:
  apps add it only when the user says they are at the place.
- Anyone can read places, from any group. Apps sort them by distance and show their age.

### Freshness

Both apps judge a place by the time it was sent (the packet's `ts`): up to 24 hours old it is current; older than
24 hours (86,400,000 ms) it is old and shown greyed; older than 7 days (604,800,000 ms) it is expired and hidden. A
place from a clock that is ahead counts as current. Vectors: `kind: place-freshness`.

## Plus Codes

Positions can also be written as Plus Codes (Open Location Code, github.com/google/open-location-code, Apache License
2.0), which map apps understand and people can read out: 10 digits, for example `8FQFF57R+M2` for 45.46420,9.19000,
an area of about 14 × 14 m.

- Encoding follows the reference: latitude × 25,000,000 and longitude × 8,192,000, each rounded down, then clipped
  (latitude) or wrapped (longitude). Decoding gives the area's corners; its center is the position. Both apps pass rows
  of the reference's `encoding.csv` and `decoding.csv` (`PlusCodeTest`, `PlusCodeTests`) and `kind: pluscode`.
- Plus Codes are not part of messages: check-ins and places keep `📍 LAT,LON`.

### Status text for other apps

"Send via another app" hands another app a plain-text check-in (not encrypted), at most 160 characters, the limit of
satellite messengers:

```
🆘 I need help — Anna. Battery 64%. 45.46420, 9.19000 ±12 m (8FQFF57R+M2) https://osm.org/?mlat=45.46420&mlon=9.19000
```

The status, ` — ` and the name (at most 24 characters) if any, `.`, ` Battery N%.` if known, then, if there is a
position: the coordinates, ` ±N m` if known, the Plus Code in parentheses, and an OpenStreetMap link. Vectors:
`kind: status-share`.

## Delivery receipts

When a phone gets a new group message from someone else, it confirms it with a receipt, so the sender sees
"Delivered to …".

- A receipt is an ordinary packet: marker `0xB3`, a new random id, the reader's clock as `ts`, ttl 16.
- It is sealed exactly like a message, but with the group's **receipt key**: HKDF-SHA256 (RFC 5869) with the group
  key as input key material, no salt, info = the ASCII bytes of `darkproof-receipt-v1`, 32-byte output.
  - Example: for the code `test-group-1` the group key is
    `f5dbd9efb376f8e61a21bd63c2db282b5dfffffd21b1df45ff2af54139a6cd89` and the receipt key is
    `738e7a99ea6284081ccd3d830969a9cb6ad62fd6161f1adedf4d34f13677e479`.
- Plaintext: a signature (see "Signatures"), then `sender` = the reader's member id in the group, `name` = the reader's display name, `text` = the ids of the
  confirmed messages (16 lowercase hex chars each), separated by single spaces, at most 200 per receipt. An id may be
  followed by a journey note (see "Journey notes").
- A receipt counts only if its `sender` is in the member list of the epoch whose receipt key opened it (see "Only
  members write in an epoch"), and not from someone removed (see "What the person removed wrote").
- A receipt confirms a message only if this phone wrote that message in the group whose receipt key opened the
  receipt. A member of one saved group can't confirm a message sent to another, and ids that are no message of this
  phone's are not kept.
- A reader counts the first 200 ids of a receipt and reads nothing after them. Anything in the text that is not 16
  lowercase hex chars is skipped.
- A reader counts once per message, with the time and name of their earliest receipt for it.
- Phones confirm only group messages: not Public channel messages, not reactions (see "Reactions"), and not their own. Each message is confirmed
  at most once, when it first arrives. A receipt goes out 2 s after the last message to confirm arrived, or 10 s after
  the first, whichever comes first, plus a random 0–20 s, so a burst of messages (a phone catching up) shares one receipt per group.
- Receipts are not sent over LoRa radios, where airtime is scarce.
- Apps that don't know receipts can't open them with the group or public key, so they store and relay them like any
  other packet and never show them.

### Journey notes

In a receipt's text, an id may be followed by one **journey note**, which says how the first copy of that message
reached the reader's phone. Apps show it to the message's writer under "How it got there".

    0123456789abcdef h3i 89abcdef01234567 h1b

- **Form:** `h`, the hops as 1 or 2 ASCII digits, then the link as one lowercase letter: 3 or 4 bytes, with a single
  space before and after like an id. Writers write no leading zero.
- **Hops:** 0 to 16: 16 minus the ttl of the first copy of that message the phone received, not below 0. A received
  ttl above 16 counts as 16 (see "TTL"). A copy that came straight from the writer's phone, or from a relay the
  writer's phone posted it to, has ttl 15: 1 hop.
- **Link:** how that first copy arrived: `b` Bluetooth, `w` a Wi-Fi network, `p` phone-to-phone Wi-Fi (Apple
  peer-to-peer, Nearby), `r` a LoRa radio, `i` an internet relay.
- **Readers** split the text at spaces (0x20); what stands between them is a token, and empty tokens don't count. A
  note is read only from the token right after an id, and belongs to that id. A token there that is not of this
  form, or says more than 16 hops, is skipped; so is a note anywhere else: at the start, after something that is no
  id, or after another note. Any other lowercase letter `a`–`z` is read as "another link". A leading zero is read
  (`h07w` is 7 hops).
- **Not ids:** notes don't count towards the 200 ids. A reader counts the first 200 ids, each with its note, and
  reads nothing after the token that follows the 200th.
- A reader counts once per message, with the time, name and note of their earliest receipt for it; an id named more
  than once in one receipt counts as it is named first.
- **Apps that don't know notes** skip them, since a note is not 16 hex chars: they take exactly the same ids from the
  receipt as before.
- **"About":** the ttl is not authenticated (see "TTL"): any phone that carries the packet can raise or lower it. The
  hops are what the reader's phone counted from the copy it got, no more, and apps say "about N hops". The link is
  the reader's own statement, signed with the receipt like the rest of it.
- **High-risk mode:** a phone in high-risk mode writes no journey notes. Its receipts are exactly as before.
- **The writer's phone** also remembers, for each of its own messages, over which kind of link it first handed the
  message on (the same letters) and when by its own clock, next to what it remembers for "Sent" (see "Forwarding
  rules"). This stays on the phone.
- **What a note tells, and whom:** a receipt is sealed with the group's receipt key, so everyone who holds that key
  (the group: the members of that epoch) learns from a note over which kind of link a reader's phone got a message
  and after about how many hops; with the receipt's time, that can show whether the reader was near another phone
  or online then. A note
  never names a phone that carried the message, and never a link id. Relays, and phones outside the group, see
  nothing of it. A note makes a receipt up to 5 bytes longer per id.
- Vectors: `kind: receipt-notes` (the same text read with and without notes), `kind: journey-note` (what a phone
  writes for a ttl and a link).

## Disappearing messages

A group can have a **timer**: each phone then removes a group message that long after the message reached it. Without
one, messages last one to two weeks (see "Keeping"). The timer is the group's, set by its admins; it is not a promise
about other people's phones: anyone in the group can copy or photograph a message, a changed app can keep everything,
and phones on a version without the timer keep messages one to two weeks.

### Where the timer lives

- **In the new key.** The 5th field of a new key's packet: type `0x03`, then the seconds as a 32-bit big-endian
  number, 5 bytes in all. A phone that joins receives no message from before it was let in, and messages expire; the
  new key is the one thing every phone of a group gets, keeps and reads again, so the timer reaches phones that join,
  phones that were away and reinstalled phones with the key that lets them in or brings them back.

  | Setting | Field | With its length |
  |---|---|---|
  | Off (one to two weeks) | `03 00000000` | `0005 0300000000` |
  | 1 hour | `03 00000e10` | `0005 0300000e10` |
  | 8 hours | `03 00007080` | `0005 0300007080` |
  | 1 day | `03 00015180` | `0005 0300015180` |
  | 1 week | `03 00093a80` | `0005 0300093a80` |

- **Reading the number:** 0 is off. 1 to 299 read as 300 (5 minutes, the shortest). 300 to 604800 as written. Above
  604800 (a week) reads as off: longer than the normal rule means nothing. Apps offer the five settings above; a
  value another app wrote is shown in its largest whole unit ("2 days", else minutes).
- **Every new key carries it:** the value its maker chose, or else the timer its maker's phone knows for the group
  (a weekly key, a join, a removal, an answer to a phone that is behind). A maker that doesn't know it writes no 5th
  field. A group made on a phone starts with the timer off, and that phone knows it.

### Whose word counts

Each epoch a phone holds has a timer: a number of seconds, off, or **not known**. The group's timer is the current
epoch's (see "Current epoch"); for rivals with one number that is the winner's, never a mix.

- **An admin's key sets it.** The timer in a new key counts if the key's maker is an admin of the epoch before it,
  judged exactly as for the admin list (see "Accepting a new key": "Only admins of the epoch before", "Several epochs
  before"). A removal is always an admin's. Anyone else's field is ignored.
- **Every other epoch inherits** the timer of the epoch before it: of the first of the epochs numbered one lower
  that the phone holds (the order of "Current epoch"). So a key without the field, from an admin or not, keeps the
  timer; only an admin's explicit `03 00000000` turns it off.
- **A phone with no epoch** can't judge its first key's maker by an epoch before. It takes the timer of the key that
  lets it in only if that key's own admin list names its maker; otherwise the timer is not known. It is never told
  a value by someone the key doesn't name as an admin.
- **Not known** stays not known through keys that inherit, until an admin's key carries the field. A phone that
  doesn't know the timer keeps the normal rule and says "Not known yet".
- **Judged again whenever the phone's keys change**, not once when a key arrives: phones that hold the same keys
  agree, whatever order the keys came in. Among "the epochs numbered one lower" counts, next to the epochs that say
  who is in, a key that arrived late and is kept only to read with (see "Accepting a new key"), if it carries the
  timer, **is the first of every epoch the phone holds at its number** (the order of "Current epoch"), its maker
  is an admin of the winning epoch before it (the first with a member list one number lower; if the phone no longer
  holds that number, as judged when the key came), **and its maker is an admin of the epoch it beats** (the first
  with a member list at its number; if the phone holds none there, the late key corrects nothing and says nothing).
  It decides nothing about people, but it is that admin's word on the timer. Without it, a phone that got the rival
  that loses first, then a member's next key, would keep the loser's timer for good. Any other late key (one that
  loses at its number, a member's, an admin's who is no admin of the winning epoch before it, or one whom the key it
  beats demoted) sets nothing and freezes nothing, and neither it nor any other key kept only to read with makes the
  chat's lines ("joined", "made an admin", the timer's line) unless it meets the same test. The cost: when one admin
  demotes another at the very moment that one changes the timer, phones that got the demoting key first keep the
  value they had until an admin's next key carries one.
  An epoch kept only to read with is never the group's timer by itself.
- **When the epochs before an epoch are gone** (a week after they were replaced), it keeps the timer worked out
  last. Phones save each epoch's timer with the epoch.
- **Keys that aren't taken say nothing:** a key that "Accepting a new key" refuses (from someone removed, through an
  old door, over the hourly limits, read a second time) is never read for its timer. So a removed admin sets nothing,
  and a key played back changes nothing.
- **Who set it** is shown only where a phone saw the value change with an admin's key, or its own user chose it. A
  key that brings a phone the timer for the first time may only repeat it.

### When a message goes

- **Counted from arrival.** A message goes when it has been on the phone for the timer's length, by that phone's own
  clock: from when the phone first received it, whenever it was written. A sender's clock that is behind can't make
  messages vanish on arrival, and a member who was away sees each message for the full time. A phone's own message
  counts from when it was first handed to another phone, a radio or a relay ("Sent"); one that hasn't left the phone
  never goes by the timer.
- **Settle time.** A value deletes nothing until the group's timer has had that value on this phone, without a
  break, for 10 minutes by its own clock; phones save the value and since when. Any change starts the wait again; a
  later key that inherits the value doesn't. The new value is shown at once. Two admins changing the timer at once
  can make the value that loses current for a moment on some phones, and deleting can't be undone. A phone that
  stays cut off from the winning key for longer follows the key it has.
- **What goes:** the message on screen; this phone's copy of it (see "Message keys"); its alert; what waited for its
  sender's key, with the message's own key if the phone still holds it (a message shown as "not verified yet"); for
  the phone's own message, who confirmed it and how it got there; reactions, which are messages;
  the group's receipts, counted the same way. Quotes of a message that went read "A message this phone doesn't have".
  Lines such as "Anna removed Marco" and the timer's own lines come from the epochs and stay.
- **The packet is still carried.** Every phone that read a message has deleted its message key, so the packet it
  stored can't be opened there again: it stays in the store as one more packet the phone only carries, for the rest
  of its 7 days (see "Forwarding rules"), so that a member who was away still gets the message. A packet that a key
  on the phone still opens (a receipt; a message sealed with the outer key, or still waiting for its sender's key)
  leaves the store instead. Either way the packet's name (id and content, "The same packet") goes on the phone's
  **gone list** until no phone passes it on any more: brought back by a neighbour, it is passed on and never shown,
  alerted, confirmed or copied again.
- **High-risk mode** (a setting of one phone, never sent) removes a group's messages a day after they reached that
  phone, counted the same way, and removes their packets too. On such a phone a message goes at whichever is due
  first.
- **Never longer than before:** the rules of "Keeping" still apply. A timer of a week doesn't keep a message that
  its epoch's end would remove.
- **When phones check:** every hour; when the app comes to the front; when the store is read; and, while the app
  runs, at the moment the next message is due.
- **Alerts** show a message's text, and an iPhone can't take an alert back while the app is suspended. In a group
  whose timer is a day or less, an iPhone's alert for a message carries no words of the message: the sender's name
  (unless the lock or high-risk mode hides it) and "New message". Check-ins, calls for help and "Are you safe?" keep
  their wording, except that a report about someone else names no one: "Someone needs help", "Reported by Anna" (the
  name in a report is the reporter's free text). Android ends an alert when its message is due.
- **This phone's clock:** set forward, it makes messages go early on this phone; set back, they stay longer.

### Changing it

- Only an admin of the current epoch who wasn't removed can change the timer. Changing it is making a new key that
  carries the new value (see "New keys"): it needs every member's encryption keys like any new key. Choosing the
  value the group already has makes no key.
- **The notice.** After the new key, the admin's phone sends one ordinary group message so that people on a version
  without the timer see what changed and that their phones don't follow it. Its text is, for example,
  `⏱ Messages here now disappear 1 day after they reach each phone. Older DarkProof versions keep them 1 to 2 weeks.`
  or `⏱ The timer is off. Messages disappear after 1 to 2 weeks.`, its 4th field is empty, and its 5th field is the
  timer field (`0005 0300015180`). A phone that follows the timer shows nothing of a group message with this 5th
  field, does not alert or confirm it, keeps no copy and takes nothing from it: the new key is the only word on the
  timer. It is not put on a LoRa radio, like a reaction.
- The chat shows a line from the epochs wherever the timer differs from the epoch before (not for a first value of
  off): "Anna set messages to disappear after 8 hours", "You set messages to disappear after 1 day", "Anna switched
  the timer off. Messages disappear after 1 to 2 weeks."; where the phone didn't see who set it, "Messages disappear
  after 8 hours" or "The timer is off. Messages disappear after 1 to 2 weeks.". The chat's header shows the value
  ("1 day") while a timer is set, and nothing while it is off or not known.

### Phones without it

- They take the new key unchanged (the timer is after the 4th field) and keep messages one to two weeks.
- They show the notice as an ordinary message.
- Their own new keys carry no timer, and phones with the timer inherit: a phone without it can't switch it off.
- A phone let in by an admin on such a version doesn't know the timer until an admin's key carries it.

Vectors: `kind: timer-field` (reading the field), `kind: timer-rekey` (a signed new key with the field: the 4th
field read as before, and the timer), `kind: timer-epoch` (who sets it and how epochs inherit), `kind: timer-rival`
(two admins at once, in either order, and a rival that came first), `kind: timer-late` (a key that came late: which
ones speak for the timer and the lines), `kind: timer-due` (the settle time and what is due), `kind: timer-notice`
(the notice message).

## Leaving the group

A phone that leaves its group, or is erased with "Tell my groups first", sends a last signed **note**. Any admin's
phone that gets it removes that member with the normal removal (see "Removals and the code"): a new key and a new code
for everyone else. The note asks for the removal; it is not one. Nothing else changes on the wire: no new packet type,
no new key format, nothing in how packets are sealed, signed or padded.

### The note

- An ordinary group message, sealed with the sender's message key in its current epoch (see "Message keys"), ttl 16.
  `text` is `👋 I left the group` (UTF-8, 21 bytes: `f09f918b2049206c656674207468652067726f7570`), the 4th field
  is there and empty, and the 5th field is type `0x05` with one byte `0x01`: framed `0000 0002 0501` after the text.
- One form for leaving and for erasing: saying "erased" could tell the wrong reader that the phone was in danger. A
  reader ignores a `0x05` field whose second byte isn't `0x01` (a newer form), or whose length isn't 2, and reads the
  message as text.
- Only the member's own key signs under their id (see "Signatures"), so nobody can make someone else leave. A text
  alone (`👋 I left the group` without the field) is an ordinary message: anyone could type or forward it.
- It goes up to the relays with the phone's own packets first, and on LoRa radios (it is small; unlike a reaction or
  the timer's notice it is not kept off them).

### Which notes count (reader)

- **L1. A valid note:** in a group (never on the Public channel); its signature checked for its sender (one waiting for
  its sender's key is decided when it checks); opened by an epoch that has a member list, not one kept only to read
  with; every number from that epoch's up to the current epoch's has an epoch on this phone with a member list, and
  at each number **the one that counts** (the first of those with a member list, in the order of "Current epoch")
  lists the sender; no removal of the sender that won is held (see "Removals and the code"); and the sender is not
  this phone. Rivals that lost don't matter: a member's weekly key that raced an admin's key letting someone in is a
  real epoch, but leaving the newcomer out of it must not silence their notes and check-in clocks for a week. A note
  from before a removal or a key that left the member out, played back after the same key was let in again, fails:
  that epoch won its number and doesn't list them. A number with no such epoch on the phone fails too: nothing there
  shows that it listed the sender. Packets older than 7 days are dropped, and an epoch is held at least
  7 days after a newer one replaced it, so a phone that held the epochs in between still holds them.
- **L2. The leaving record.** On a valid note the phone saves: group, member id, the note's `ts`, the number of the
  epoch it opened with, and when the phone took it. Later notes from the same member change nothing while the record
  lasts. It ends when the member is no longer in the current epoch, when the group is left, or 7 days after the phone
  took it. If a removal that won took the member out, the phone keeps that removal's epoch id with the record, for
  the chat's line, while it holds that epoch.
- **L3.** Nothing from that member dated after the note's `ts` is shown, alerted, confirmed or counted, as long as
  the record or the kept removal lasts; a message dated at or before it is shown as usual.
- **L4. Admins' phones.** If the phone is an admin of the current epoch and wasn't removed, it waits a random 0 to
  3 s; then, if the member is still in the current epoch and no removal of them is held, it makes the normal removal
  of that member. If it can't (an encryption key of a member who stays is missing), it tries again at the hourly
  check, beside finishing removals that lost (see "Two new keys at the same time"), while the record lasts. Two
  admins acting at once make rival removals of one person, settled by the existing rules.
- **L5.** Other phones only show the line and apply L3.
- **L6.** The note is never a chat bubble, an alert or a receipt. The chat shows a line instead (below). The phone keeps
  no copy of it (see "This phone's copy"): the record is what counts.

### Leaving (writer)

- **LW1.** Only from a phone with a current epoch in that group, that wasn't removed and isn't still waiting to be let
  in; a note is never queued until a phone is let in. A phone that can't write says so: "This phone isn't let in, so
  My group can't be told." It leaves without a note.
- **LW2. The only admin.** If the phone is an admin of the current epoch and no other member of it is, it asks who runs
  the group first. With people chosen, its phone makes an ordinary admin new key that adds them to the admins (see
  "New keys"), then seals the note in that new epoch; a successor's phone then removes the leaver like anyone else,
  also a leaver who created the group. If the key can't be made (a member's encryption key is missing): "This phone
  can't hand over now: it doesn't have every member's keys.", with "Leave without telling" and "Cancel". "Leave
  without choosing" leaves no admin: nobody can let people in or remove anyone.
- **The leaving phone never removes itself.** A removal is made by someone who stays: one made by the leaver would
  give the leaver the new secret and the new code, since it made them.
- **LW3. Leave:** seal and send the note, then wait until a phone, a radio or a relay took it ("Sent") or 15 s, with
  "Leave now". Then the group is forgotten as before, except that the note's own packet stays in the store as a
  packet the phone only carries (it can't open it: the keys are gone), until its 7 days end, so it still reaches the
  group when phones come into reach later; a copy of the note, if the phone made one, goes. Its id leaves the phone's
  lists with the group's other messages. While it waits, the leaving phone makes no new key in that group (no removal
  of another member who left, no finishing of a removal that lost, no answer to a phone that is behind): it would
  make epochs and a code it then forgets.
- **LW4. Erase everything** with "Tell my groups first" on: seal a note in every saved group where LW1 allows; wait
  until each was taken or 15 s, with "Erase now"; then erase as before (the notes go with everything else). Erasing
  never waits on the network for longer: the phone saves the erase's marker before the notes go, so an app that ends
  while it waits erases at its next start, and the wait ends at once if the app goes to the background and comes
  back, or runs out of time there. **An erase always starts**, also while a leave runs: the leave stops where it is
  (during its hand-over key, its note's wait or its shutdown), the Keychain goes at once, and the erase goes on as soon
  as the leave's shutdown is over. It never says "try again".
- **LW5.** Nothing else is sent: no receipts are awaited, nothing is sent again after the group is forgotten.

### In what order

- An admin removes the member while the note travels: admins' phones find them out already (L4 does nothing).
- An admin removes someone else at the same moment as the automatic removal: rival removals of one number, existing
  rules; the person in the removal that lost is still in the current epoch and an admin's phone finishes it.
- No admin reachable for days: the note waits on phones and relays for up to 7 days, its packet's life; any admin's
  phone that gets it in that time acts. After that the member stays listed until an admin removes them by hand.

### What the screens say

- **Leave:** "Leave My group?" / "This phone forgets the group, its messages and its keys. A note asks the group's
  admins to remove you and change the key. To come back you need a new invite." / "Stay" / "Leave My group" (red).
  The only admin first: "Who runs My group after you?" / "Admins can let people in and remove people." / the members
  to choose / "Continue" / "Leave without choosing" ("Nobody will be able to let people in or remove anyone.").
  While waiting: "Telling My group…" / "Waiting for a phone or relay to take the note." / the seconds left /
  "Leave now". Afterwards: "You left My group", or "You left My group. The note didn't leave this phone in time. It
  goes when a phone comes into reach."
- **Erase:** the switch "Tell my groups first", "Asks their admins' phones to remove this phone and change the key";
  after the hold "Telling your groups…" / "Waiting for a phone or relay to take the note." / the seconds left /
  "Erase now". The last screen names no group (the phone may be taken at that moment): "This phone is empty", and
  "The notes left this phone.", "1 of 2 notes didn't leave this phone in time.", for a single note "The note left
  this phone." or "The note didn't leave this phone in time." (never "1 of 1 notes"), or, with the switch off or no
  group this phone could write in, "Your groups were not told."
- **Other phones:** while the member is listed, "Marco left the group" on an admin's phone and "Marco left. Waiting
  for an admin's phone to change the key." on anyone else's, at the note's time; once a removal took them out, the
  removal's line reads "Marco left the group" instead of "Anna removed Marco".
- Never: "Their phones remove this one", "removed from the group", "your messages are deleted from their phones",
  "My group was told to remove it". The note leaving the phone isn't the group getting it.

### Phones without it

They show `👋 I left the group` as an ordinary message (a reader that knows replies, reactions and the timer reads a
`0x05` field as none). Their admins don't act by themselves; an admin can read it and remove the member by hand. Their
weekly keys keep listing the leaver until someone removes them.

### Abuse and failure

| Who or what | What happens | Why |
|---|---|---|
| A member forges a note for Marco | Not valid | Only Marco's key signs under his id |
| Someone with only the code | Can't write a group message at all | Doors and epochs |
| A thief with Marco's unlocked phone sends it | Marco is removed | The same harm as erasing his phone; he can be invited again |
| An old note played back after Marco was let in again with the same key | Ignored | L1: the removal's epoch doesn't list him |
| A removed member's note | Not shown, does nothing | L1 |
| A changed app keeps writing after its note | Not shown | L3 |
| A changed app keeps reading until it is removed | Possible until an admin's phone acts | The note asks for that removal; the screens promise no more |
| Every admin's phone acts at once | Rival removals of one person, one wins | 0 to 3 s delay and the rules for rivals |
| No admin in reach for 8 days | The member stays listed | 7-day packet life; removing by hand |
| The only admin leaves without choosing | Nobody can let people in or remove anyone | An explicit choice, with that warning |
| Erase with nothing in reach | Erased after at most 15 s, the group not told | The last screen says so |
| Leave keeps the note as a carried packet | A seized phone holds one ciphertext in the group's mailbox | It holds many packets it carries; it can't open this one |

Vectors: `kind: leave-field` (reading the field), `kind: leave-note` (a sealed note, and what readers with and without
the feature make of it), `kind: leave-valid` (which notes count, L1, with rivals that lost), `kind: leave-admin` (what an admin's phone does,
L4), `kind: leave-after` (what is shown from the leaver, L3).

## If I go quiet

A member can start a **check-in clock**: if no check-in of theirs reaches a phone of the group by the deadline, that
phone tells its owner. The group's phones keep the clock, so it still works when the member's phone is off or gone;
only phones that got the clock message can tell anyone. Nothing else changes on the wire: no new packet type, nothing
in how packets are sealed, signed, padded or keyed.

### The clock message

- An ordinary group message, sealed with the sender's message key in its current epoch (see "Message keys"), ttl 16.
  The 4th field is there and empty; the 5th field is type `0x04` and the **deadline**: ms since 1970 UTC by the
  sender's clock, a signed 64-bit big-endian number (9 bytes in all). Deadline `0` stops the sender's clock.
  Example: `ts` 1793807238631, 1 hour: deadline 1793810838631, field `04 000001a1a7753467`, framed
  `0000 0009 04000001a1a7753467` after the text. The empty 4th field and the 5th add 13 bytes.
- `text` is what every app shows; nothing is read from it but a position. Start or more time:
  `⏳ Check on me if I don't check in within 1 hour` (49 bytes). The duration is `deadline - ts` rounded to the
  nearest minute (half a minute rounds up), written `N minutes`, `1 hour`, `N hours`, or `1 hour 30 minutes`,
  `3 hours 15 minutes`. It is relative to the message's own time, which every app shows, so time zones don't matter.
  Stop: `⏳ Clock stopped. No need to check on me`.
- With a position, only if the sender turned on location sharing and chose "Add where I am": the check-in suffix
  ` · 📍 45.46420,9.19000 ±12m` at the end of the text, read exactly as in "Check-ins".
- It is alerted, confirmed with a receipt and shown like a message (an app that knows the field shows a card), goes on
  LoRa radios (it matters like a check-in; without a position it fits MeshCore with a name up to about 35 bytes), and
  this phone's copy (see "Message keys") keeps the field.

### Which clocks count (reader)

`ts` is the message's signed real time.

- **Q1.** A clock message, or a check-in that cancels one, counts only in a group (never on the Public channel), from
  a packet whose signature checked for its sender (one waiting for its sender's key is decided when it checks), opened
  by an epoch with a member list, the epoch that counts at every number from that one up to the current one listing
  the sender, as for a leave note (see "Leaving the group", L1), and not dated after the `ts` of a leave note of the
  sender that is held (L3; one dated at or before it still counts). A removed member's never counts; nor one sealed
  before a removal and played back after the same key was let in again. A Wi-Fi board writes none.
- **Q2.** A valid deadline is `0`, or `ts + 5 min <= deadline <= ts + 24 h`, both ends included. Any other value, or
  a `0x04` field of another length: the field is ignored and the message is an ordinary text.
- **Q3.** Per group and member, the valid clock message with the greatest `ts` counts; equal, the greater packet id
  (16 lowercase hex characters); equal, the greater mark (the first 8 bytes of its signed digest, as replies name a
  message, see "Replies"). Arrival order doesn't matter; an older one never replaces a newer one.
- **Q4.** The clock is **armed** while the message that counts has a deadline other than `0` and no **cancelling
  check-in** reached this phone: an own check-in of that member (text starting with `✅` or `🆘`, see "Check-ins"), as
  in Q1, with a `ts` greater than the clock message's. Reports (`👤`), reactions, "Are you safe?" and other messages
  don't cancel. A `🆘` cancels the clock and alerts as help, as always. A stop cancels by Q3: it is the newer clock
  message.
- **Q5.** Learned before the deadline: the phone schedules one alert at the deadline by its own clock, and takes it
  back when a cancelling check-in or a newer clock message arrives while the app runs.
- **Q6.** At the deadline, if still armed: one alert. People shows the member as overdue until a cancelling check-in
  arrives or 12 hours after the deadline, whichever comes first. A check-in dated after the deadline shows "Checked in
  at 19:10" instead, for those same 12 hours; an alert still showing for that clock is removed.
- **Q7.** Learned after the deadline (the phone was away): if `now <= deadline + 12 h` and armed, the alert comes at
  once, with "This phone learned of it late."; later than that, no alert and nothing in People, only the chat card.
- **Q8.** The sender's own phone never alerts its user about its own clock. For a clock of 20 minutes or more
  (`deadline - ts`) it reminds its user 10 minutes before the deadline, with "I'm safe" under the reminder unless
  "Lock DarkProof" is on. On iPhone "I'm safe" under an alert (the reminder, "Are you safe?") asks for the phone's
  unlock first: whoever holds a locked phone can't stop its clock from the lock screen.
- **Q9.** When the member is no longer in the current epoch because of a removal that didn't follow a valid leave
  note of theirs, their clock is dropped without an alert. A valid leave note, and the removal that follows it (L4),
  **don't end a clock**: it keeps running and alerts at its deadline as usual. Someone may erase or leave because they
  are in danger, the case the clock exists for. Only a cancelling check-in or a stop of theirs that counts (Q1) ends it.
- **Q10. The record.** Per group and member: the clock message's packet id and mark, `ts`, deadline, position (none in
  high-risk mode on this phone), when this phone took it, whether its alert was posted or handed to the system for the
  deadline; for the phone's own clock, who confirmed it (Q14); and the `ts` of their latest cancelling check-in. Saved
  apart from the messages, so the clock survives its message being deleted by the group's timer or high-risk mode, and
  app restarts; kept in the app's own storage, out of device backups (on iPhone a file excluded from backups, never the
  preferences). The position goes once the group's timer would have deleted the clock message (counted from when the
  phone took the clock), and at once in high-risk mode. A record is kept until `ts + 36 h`
  (past the latest deadline, `ts + 24 h`, and the 12 hours after it), also once cancelled, without its position, so
  that a copy of an older clock read again can't arm it again. In high-risk mode it goes at `ts + 24 h`, never before
  the deadline. It goes at once when the member is dropped (Q9) and when the group is left. At every start the records
  are rebuilt from the stored messages too, the newer winning (Q3); a clock whose record would already have gone is
  not taken again.
- **Q11.** The alert and People show when the newest position known of that member was sent: from the clock message or
  any check-in of theirs, whichever has the greatest `ts`.
- **Q12. Limits.** One clock per member per group (Q3); at most 100 records per group, the oldest going; on iPhone at
  most 32 clock alerts wait with the system in all (iOS keeps 64 per app), nearest deadlines first, the rest handed
  over as earlier ones go. A phone posts at most one clock alert about a member per group every 10 minutes (one due
  meanwhile comes when the 10 minutes are up), and an alert learned late only for a clock newer than the last one it
  alerted about that member: backdated clocks can't make every phone ring again and again.
- **Q13. Who it is from.** The alert and the chat card name the member by their display name only if their person
  emoji were checked on this phone and no other member of the group has the same name (by name key, as in "Reports
  about others"). A member not checked: "Anna (not checked)"; a checked one whose name another member has too: "Anna ·
  🐶🐱🦁🐴", the first four of their person emoji. A member who calls themselves "Anna" can't make the group read an
  alert as being about the real Anna.
- **Q14. Kept by.** The sender's phone keeps, in its own clock's record, the members whose phones confirmed the clock
  message (delivery receipts), so the list outlives the message when the group's timer deletes it, and shows only those
  who are members now (not someone who left or was removed since: their phone forgot the group).

### Starting and stopping (writer)

- **QW1.** Only from the "If I go quiet" screen, in a group where the phone has an epoch and wasn't removed. Choices:
  30 minutes, 1 hour, 3 hours, 8 hours. "Add 30 minutes" sends a new clock message with the old deadline plus 30
  minutes (from now if it has passed), at most 24 hours from now. "I'm safe, stop the clock" sends the ordinary check-in
  `✅ I'm safe`, which cancels (Q4). "Stop without checking in" sends the stop.
- **QW2.** Never on the Public channel, and never held until the phone is let in: a phone that can't write in the group
  says "This phone isn't let in, so it can't send a clock to My group." and sends nothing.

### Alerts, and what can't be guaranteed

- **iPhone:** a local notification for the deadline, its words decided when it is scheduled (no name and no text with
  "Lock DarkProof" or high-risk mode on), replaced when a newer clock arrives, removed when a cancelling check-in
  arrives while the app runs (on screen, or woken in the background for Bluetooth). All clock alerts that wait are made
  again when the lock or high-risk mode is switched. Its identifier is a hash of the group's id and the member's, so
  neither sits in the system's alert database. A check-in that reaches the relays or a neighbour while the app is
  suspended isn't seen until the app runs, so the alert can come although the member checked in; the words cover that.
  It doesn't come while the phone is off, or with alerts off for DarkProof.
- **Android:** a timer in the running service, and a backup inexact alarm (`setAndAllowWhileIdle`) to a receiver that
  posts the alert unless the record was cancelled. Doze can delay it by minutes; no exact-alarm permission is asked
  for. Alarms are lost on a reboot or a force stop and set again when the app or its service next starts. The screen
  adds "It can come a few minutes late."

### What the screens say

- **Own screen**, "If I go quiet": "If you don't check in on time, the phones that got your clock tell their owners."
  / "It still works when your phone is off or gone. Only phones that got the clock can tell anyone." The chips
  "30 min", "1 hour", "3 hours", "8 hours"; with location sharing on, the switch "Add where I am" ("Where you are now
  goes with the clock"); "Start the clock". Running: a ring and the time left, "Ends 18:30", then from the clock
  message's delivery receipts (Q14), never from the member list: "Not confirmed by any phone yet. If no phone has it,
  nobody is told.", "Kept by Marco so far", "Kept by Marco and Sara so far", "Kept by Marco, Sara and 2 more so far". Buttons
  "I'm safe, stop the clock", "Add 30 minutes", and the text button "Stop without checking in". Toasts: "Clock sent to
  your group", "30 minutes added", "“I'm safe” sent. The clock stopped.", "Clock stopped" (with nothing in reach,
  that it waits for a phone in range). Below: "Phones on older DarkProof versions only show the message."
- **Chat card:** "Anna started a check-in clock" / "If no check-in from Anna reaches this phone by 18:30, it tells
  you."; a stop: "Anna stopped the check-in clock". The name as in Q13 ("Anna (not checked) started a check-in clock"). Its own: "You started a check-in clock" / "Ends 18:30. Phones that
  got it tell their owners if you don't check in."
- **People:** armed, "Check-in clock · ends 18:30"; overdue, "Clock ran out at 18:30 · no check-in has reached this
  phone"; then "Checked in at 19:10".
- **Alert** (names allowed): "Anna's check-in clock ran out" / "No check-in from Anna has reached this phone since
  17:30. Anna's phone may just be out of reach. Last position known: 18:02." Learned late: "Anna's check-in clock ran
  out at 18:30", and the body ends "This phone learned of it late." With "Lock DarkProof" or high-risk mode on:
  "DarkProof" / "A check-in clock in your group ran out. Open DarkProof." On screen, a neutral banner (not red) with
  the alert's title and "No check-in from Anna has reached this phone. In My group."
- **Reminder** (Q8): "Your check-in clock ends at 18:30" / "Check in now?", with "I'm safe"; with the lock or high-risk
  mode on, "DarkProof" / "Your check-in clock ends soon. Open DarkProof."
- Never: "Anna is missing", "Anna didn't check in", "your group will be told", "Marco and Sara are told".

### Phones without it

They show `⏳ Check on me if I don't check in within 1 hour` as an ordinary message with its time, and the stop text;
they alert it as an ordinary message and keep no clock (a reader that knows replies, reactions, the timer and the leave
note reads a `0x04` field as none). The sender's screen counts only receipts, so it never claims that they keep one.

### Abuse and failure

| Who or what | What happens | Why |
|---|---|---|
| A member sends 50 clock messages | One record (the newest), one alert; backdated ones at most one alert in 10 minutes | Q3, Q12; per-link pacing bounds packets |
| A member names themselves "Anna" and sends a backdated clock | The alert reads "Anna (not checked)", or "Anna · 🐶🐱🦁🐴" | Q13 |
| Anna erases her phone or leaves while her clock runs | The clock still alerts at its deadline | Q9 |
| Someone with Anna's locked phone taps "I'm safe" on the reminder | The phone asks for its unlock | Q8 |
| A deadline years ahead, or in the past | The field is ignored, plain text | Q2 |
| Someone writes a clock under Anna's id | Not counted | Q1: only Anna's key signs under her id |
| A removed member's clock | Never counted, or dropped (unless the removal followed their leave note) | Q1, Q9 |
| An old clock message played back | Ignored if a newer one is held; else it is Anna's own past clock, valid only within its 24 hours | Q2, Q3, Q10; packets older than 7 days are dropped |
| Anna's phone clock is 20 minutes fast | Alerts 20 minutes early on other phones | Deadlines are in her clock; packets more than 10 minutes ahead are dropped; the screens show the time |
| Anna checks in but it reaches only some phones | Those phones don't alert, the others do | "has reached this phone" |
| This phone is off at the deadline | No alert while it is off | Stated limits |
| The clock message reaches no phone | Nobody is told | The sender's screen says so, from receipts |
| Someone with Anna's unlocked phone stops her clock | It stops | As sending "I'm safe"; the lock is the protection |
| An alert on a seized phone | No names, no text with the lock or high-risk mode | Alerts follow the same rules as message alerts |
| A position given away | Only with location sharing and "Add where I am" on | As in check-ins |

Vectors: `kind: quiet-field` (reading the field), `kind: quiet-message` (a sealed clock message, and what readers with
and without the feature make of it), `kind: quiet-valid` (Q2), `kind: quiet-pick` (Q3), `kind: quiet-cancel` (Q4),
`kind: quiet-late` (Q5 to Q7), `kind: quiet-text` (the text).

## Finder

Someone who sent "I need help" may be near but out of sight: in another room, under rubble, in a pocket. Finder lets a
group member walking nearby tell which Bluetooth neighbour is that person's phone and how strong its signal is on their
own phone. It shows a word for the strength and whether it is getting stronger or weaker, never a distance or a
direction: phone-to-phone signal strength at one spot varies by 10 to 20 dB with a body, a wall or a pocket in between,
and a number of metres would be trusted and wrong.

### The finder note

- **F1. Field:** a "needs help" check-in (text starting with `🆘`) in a group may carry in its 5th field the type
  `0x06` and the sender's current link id (see "Link ids") as 6 raw bytes: `06 a1 b2 c3 d4 e5 f6` for `a1b2c3d4e5f6`.
  The 4th field is there and empty.
- **F2. Writers** add it to each message of their own whose text starts with `🆘`, in a group, while the switch "Let
  my group find this phone" is on (default on) and high-risk mode is off. Never on the Public channel, never to `✅`,
  reports (`👤`), "Are you safe?", replies or reactions.
- **F3. Radio first:** if the packet would fit 163 bytes (MeshCore) without the note and not with it, the note is left
  out. The note adds 11 bytes (the empty 4th field's length, the 5th field's length, 7); see "Needs and head count"
  for the rest of the packet. A check-in with a battery level fits 163 bytes only with a name of a few bytes at most,
  so it nearly always carries the note.
- **F4. Readers** take it only from a group message that opened with a group's key and whose signature checked for a
  member of that epoch, only when the text starts with `🆘`, the field is exactly 7 bytes and its type is `0x06`.
  Anything else in the 5th field is ignored (see "The 5th field"). Until the signature checks, nothing is taken. The
  note never makes a message a reply, a reaction or a notice: it is shown as the check-in it is.
- **F5.** A person's note is the one in their most recent check-in, of either kind. A later `✅` check-in, or a later
  `🆘` without a note, ends it: "I'm safe" ends being findable.
- **F6. After a restart** the old link id is gone. Once per start, if this phone's newest own check-in in a group is
  "needs help", under 24 hours old, with the switch on and high-risk mode off, the phone sends it again (the same needs
  and head count, a fresh battery level and position, the new link id), at most once in 10 minutes per group. It alerts
  the group again, as any "needs help" does.

What it reveals: everyone who holds the group's key learns which link id is this member's until their app restarts.
During that time a group member, or anyone who has a member's phone or key, can recognise this phone over Bluetooth by
linking to it and reading its HELLO. Nobody outside the group learns anything new. A later check-in without the note
ends it for readers, and a restart ends it for everyone. Phones in high-risk mode, and phones whose user turned the
switch off, never add it.

### Reading the signal

Only on the searcher's phone. Nothing about the match or the readings is ever sent, stored or put in a receipt.

- **F7. Where readings come from:** Finder compares the note's link id with the link ids this phone's Bluetooth LE
  links reported in HELLO. On a link this phone dialed (it is the central), it reads the connection's signal strength
  once a second. On a link the other phone dialed, it may open a GATT client on the same connection only to read the
  signal strength: it discovers nothing, writes no descriptor and sends no HELLO, so it is no link (nothing changes in
  "Duplicate links" or "Limits"), and it is closed when Finder closes. Whether phones allow this is not known yet. A
  phone linked only over Apple peer-to-peer, Nearby or Wi-Fi gives no reading. A reading outside −110 to −20 dBm
  (iOS gives 127 for "not available", some phones 0) is none.
- **F8. Not in reach:** no reading yet, or none for the last 10 seconds. Everything below starts over.
- **F9. Smoothing:** at a second with a reading, m is the median of the readings of the last 5 seconds (this one and
  the 4 before; with an even number of them, the mean of the middle two). The smoothed value S is m at first, then
  `0.3 * m + 0.7 * S`, in IEEE 754 double precision in that order. At a second without a reading S stays.
- **F10. Levels:** "Here" if S ≥ −60, "Very close" if S ≥ −70, "Near" if S ≥ −82, else "Far".
- **F11. Bands:** the band goes up at once to the level of S, but to "Here" only once S has been −60 or above for 3
  seconds in a row (until then "Very close"). It goes down only when S is more than 3 dB below the band's lower edge
  (−82, −70, −60), and then to the level of S. The thresholds are starting points for the real-phone tests, changed on
  both platforms together.
- **F12. Trend:** the mean of the readings of the last 5 seconds against the mean of the 5 seconds before: 4 dB or more
  "Signal getting stronger", −4 dB or less "Signal getting weaker", else "Signal steady"; no trend line while either
  has no reading.

### What the screens say

- The whole Finder screen stays hidden behind a trial switch (`finderTrial`, off by default) until the real-phone tests
  pass. The note is read and written either way.
- A person whose latest check-in is "needs help" with a note gets "Find by Bluetooth" on their page, beside "Find" (the
  compass) if they shared a position. The screen is "Finding Marco": rings that light up one per band (green only at
  "Here"), the band's word and a line under it, the trend line. Lines: "Keep moving. This phone hasn't heard Marco's
  phone yet." / "Keep walking and watch the signal." / "Keep going the way the signal gets stronger." / "Within a few
  rooms. Call out and listen." / "Within a few steps. Look around."
- Always on the screen: "Bluetooth can't tell direction, only stronger or weaker. Walls and people weaken it."
- Linked only over Wi-Fi or Apple peer-to-peer: "Linked to Marco's phone, but not over Bluetooth, so the signal can't
  be measured." Linked only by a link Marco's phone dialed, where this phone can't read the signal: "Linked to Marco's
  phone, but this phone can't measure the signal on this link." Not linked: "Finder works only when this phone and
  Marco's are linked by Bluetooth. If Marco's phone restarted after asking, it can't be recognised until it sends a
  new check-in." Bluetooth off: "Turn on Bluetooth to search."
- "I'm with Marco" sends the ordinary group message `I'm with Marco` (no new format, never a check-in) and says "Your
  group is told you're with Marco."
- A short vibration when the band changes; the screen stays on while Finder is open; VoiceOver says the band when it
  changes, not every second. No animation runs on its own.
- Settings: the switch "Let my group find this phone", "When you send “I need help”, your group can tell which nearby
  phone is yours until DarkProof restarts." In high-risk mode it is off and greyed: "Off in high-risk mode."

Vectors: `kind: finder-field` (what a reader takes from the 5th field), `kind: finder-fit` (radio first, with the
packet's size), `kind: finder-bands` (signal readings, one a second, to bands and trends), made by
spec/make-reply-vectors.py (argument `finder`).

## Forwarding rules

- **Flooding:** each packet goes to every connected peer except the one it came from.
- **Deduplication:** a packet already seen (see "The same packet" below) is not shown or stored again. It is forwarded
  again only if it comes with more hops left than the copy the phone holds (next rule).
- **TTL:** forwarded copies carry `ttl - 1`, and only if the received ttl is greater than 1. New messages start at ttl 16.
  A received packet with ttl above 16 (decoding accepts up to 64) is stored and forwarded as if it had ttl 16.
  The ttl is not authenticated, so the first copy to arrive doesn't decide how far a packet goes: when a packet the
  phone still holds for passing on arrives again with a higher ttl, the phone keeps the higher ttl, in its store too,
  and forwards the packet again to every connected peer except the one that copy came from. A copy with the same or a
  lower ttl is ignored. The ttl must rise each time, so one packet is forwarded again at most 15 times.
- **Time:** a packet whose `ts` is more than 7 days old, or more than 10 minutes ahead of the receiver's clock, is
  dropped: not stored, shown or forwarded (it is not marked as seen, so it can come again later). Packets saved on the
  phone that are more than 10 minutes in the future are removed when the app reads its store.
- **Passing on:** up to 500 packets, the newest of the last 7 days. All of them are sent to every newly connected
  peer, except the packets that peer brought itself: it has them, and what came from the relays must not go back up
  (see "Publish"). A packet another peer has since brought with a higher ttl does go to it.
- **Storage on the phone:** every packet seen is saved, so messages are still there after a restart. The store is
  trimmed when the app starts, and whenever it grows 10% past its cap: packets older than 7 days that no saved group
  can read or that are door packets (next rule), Public packets beyond 2,000 (while one sender holds more than 500 of
  them, that sender's oldest go first, otherwise the oldest of all; the senders whose signature the phone can't check
  count together as one sender, and so do all introductions; the phone's own go last), and then, while the store
  holds more than 40 MB (encoded packet sizes) or 20,000 packets: first packets no saved group can read, oldest first,
  then door packets, then the oldest Public messages, and only then the oldest messages and receipts of the saved
  groups.
- **Door packets:** an introduction or a new key that came through a saved group's door is that group's own, kept like
  its messages, only if this phone wrote it or it is signed by someone in the group: in the member list of an epoch
  the phone holds that isn't kept only to read with, and not taken out by a removal that won. The door opens with the
  code alone, so any other is a door packet. Door packets have a room of their own inside the cap, 4 MB and 1,000
  packets for all saved groups together: beyond it their oldest go, at each trim, and the store is also trimmed when
  they reach twice that room. They never push out a message.
- **Sent:** a phone remembers (up to 2,000) which of its own messages it has handed to another phone, a radio or a
  relay, so "Sent" survives a restart, and for each over which kind of link it first left and when (see "Journey
  notes"). Its own reactions are not among them.
- **High-risk mode ("Keeps less"):** a phone notes when each message and receipt of its groups reached it, and in
  high-risk mode removes each one 1 day after that (a message it wrote: 1 day after it was first handed to a link, and
  never before; a receipt it wrote: 1 day after it was written), while introductions, new keys, Public packets and
  packets it can't open keep the rules above. It keeps the name of each packet removed this way (the key under "The
  same packet", with the packet's `ts` and its group's id, no content; at most 5,000, each until its `ts` is 7 days
  old): one that arrives again is passed on, but never shown, alerted, confirmed or copied again, and it is stored
  again only if no key on the phone opens it any more.

- **The same packet** means the same id, the same `ts` and the same content (the first 8 bytes of SHA-256 of `ts`, 8
  bytes big-endian, followed by `cipher`). The encryption is bound to `ts`, so a copy with another `ts` can't be opened:
  it is a different packet, like one with other content. The ttl is not part of it. Packets with one id and a different
  `ts` or content are different packets, however many there are, so junk sent first under an id can't stop the real
  packet; the limits on links are what bound them. In the store, the first packet kept under an id goes by that id,
  and any other packet with that id is kept too, under its id and content together (the key above): none replaces
  another, so junk sent first under an id doesn't keep the real packet out of the store either. The queue in front of
  a LoRa radio tells packets apart the same way: the same packet goes over the radio once, whatever its ttl, and junk
  under its id doesn't keep it off. So do the queues for the relays, and what a phone remembers as posted there (see
  "Publish"): junk under an id neither takes the real packet's place nor counts as it.
- **A full queue:** while one sender holds more than a quarter of what is stored, its own oldest packets go first. A
  sender is whoever is at the other end of a link: the IP address on a Wi-Fi network and the device on Bluetooth LE
  (its Bluetooth address; on the iPhone, the identifier iOS gives it), so all links from one address share one
  quarter, at once or one after another; on the other transports, the link. The packets a phone held before a
  restart count together as one sender's, except those a relay brought. Otherwise, while the packets the phone
  didn't write itself hold more than half of what is stored, the oldest of those goes first: no number of links
  pushes out the phone's own packets.
- **Limits on links:** a packet with fewer than 98 bytes of `cipher` is junk and is dropped. A phone takes at most 3,000 new
  packets a minute from one sender (see "A full queue"; relays have their own limit, see "Limits on relays"): closing a
  link and opening another doesn't start a new minute. A copy that is forwarded again because of its higher ttl counts
  as one of them.
- **Keys tried:** a phone tries a packet only with the keys whose mailbox is the first 2 bytes of its id.
- **Node ids prove nothing:** a link claiming the id of a phone already linked doesn't replace that link, unless it comes
  from the same address (Wi-Fi) or was made at the same moment (Bluetooth). Nor does the first link under an id keep
  the phone itself out: a later link from another address (Wi-Fi), or one the other side dialed over another
  connection (Bluetooth), runs beside it. At the link limit, a link at random gives way, whatever its age and at most
  once in 30 s, so that nobody can hold every place, neither by staying nor by making its links anew; on Wi-Fi, the
  address that holds the most links gives up its oldest first.

## Transports

| Transport | Framing |
|---|---|
| Nearby Connections (Android only) | Service id `it.blackout.mesh.v1`, strategy `P2P_CLUSTER`. One `BYTES` payload per packet. |
| Wi-Fi networks (Android and iPhone) | TCP. 4-byte length, then the frame. See below. |
| LoRa via Meshtastic | The packet is `Data.payload` on port 256 (`PRIVATE_APP`), broadcast on channel 0 with hop limit 3. The whole packet must be ≤ 233 bytes. |
| LoRa via MeshCore | Channel data on the `#darkproof` channel, flooded. The whole packet must be ≤ 163 bytes. See below. |
| Bluetooth LE (Android and iPhone) | See below. |
| Internet relays (Android and iPhone) | Nostr events over WebSocket. See below. |
| Apple peer-to-peer (iPhone only) | MultipeerConnectivity. Each packet is one `.reliable` message. See below. |

## Internet relays

While there is internet (Wi-Fi, 4G, satellite), phones also exchange packets through free public **Nostr** relays
(NIP-01), optionally through Tor. DarkProof runs no server. The relays are one more peer for forwarding: a phone sends
them every packet it has and gets the packets of its mailboxes, so a phone that still has internet during a blackout
bridges its neighbourhood to the rest of the network.

### Mailboxes

The first 2 bytes of a new packet id are its **mailbox**; the other 6 bytes are random. The mailbox of a group is the
first 2 bytes of HMAC-SHA256(group key, `darkproof-relay-mailbox-v1`). Group messages and their delivery receipts use
the group's mailbox; Public messages use the mailbox of the Public channel key. Anyone can read the mailbox from the id
of any packet, so a phone can bridge packets it cannot open. Packets from older apps have random ids.

- Test values: group `test-group-1` → mailbox `6a44`; the Public channel → `9576`.
- Someone who sees packets can tell which ones share a mailbox, not what they say. Many groups share each of the
  65,536 mailboxes. Ids keep 48 random bits, so duplicates stay negligible.

### Events

- **Kind:** `3871`, a regular (stored) kind. It is not in the NIPs list or the registry of kinds, and no events of
  nearby kinds were found on 11 public relays (2026-11).
- **Relays:** `wss://nos.lol`, `wss://relay.damus.io`, `wss://relay.primal.net`, `wss://nostr.mom`,
  `wss://nostr.oxtr.dev`, `wss://relay.ditto.pub`. All free, with no sign-up, payment or proof of work; all keep events
  at least 7 days, and all stored a 22 KB event of this kind from a new key (tested 2026-11-06). Phones use all of them
  at once, so one being down doesn't matter. Relays with a "web of trust" rule refuse large events from new keys.
- **Tags:** `["t", "dp1-<mailbox as 4 hex digits>"]`, `["x", "<packet id>"]` and
  `["expiration", "<created_at + 604800>"]` (NIP-40: relays may delete it after 7 days).
- **Content:** the packet (0xB3 encoding) in standard base64.
- **Date:** `created_at` is the current time rounded down to a multiple of 1024 seconds (about 17 minutes).
- **One set of links per group:** each saved group has its own connection to each relay, its own key and its own
  subscriptions, and so does the Public channel. A group's links start up to 8 s apart at random. A packet this phone
  wrote (a message, receipt, introduction or new key) goes up on the links of its group; every packet it only passes
  on goes up with the Public channel's, whatever mailbox its id names. A sender chooses the id, so the mailbox of
  someone else's packet must never pick the links and key: a neighbour could send junk under a mailbox and see, from
  the key it comes back under, whether the phone holds that mailbox. Nothing a phone sends on one group's links may
  let a relay match them to its other links. Without Tor, relays still see the same internet address on all of them.
  With Tor, the default, each group has its own circuits.
- **Key:** a new random secp256k1 key every time the app starts, for each group, so relays can't link sessions or groups
  by key. The signature (BIP-340) only satisfies the relays: who wrote a message is inside the encrypted packet.
- **Mailboxes of a link:** its group's door and epoch mailboxes, or the Public channel's (`9576`). A link takes only
  packets of these mailboxes, and packets its own `#x` lookup named while that lookup is open (see "Publish"). Any
  other packet a relay sends on it is dropped: a relay chooses what it sends, and a packet planted on one group's link
  must not be passed on, or named on another link.
- **Subscription ids:** the id of a live subscription is `live` and 8 random bytes in hex, that of a lookup `have` and
  8 random bytes in hex, new each time and for each group's links; catch-up ids count up on each link (`cu1`, `cu2`,
  …). No id is made from a clock or anything else the links of one phone share.
- **Subscribe:** on each relay link, one live subscription for all of them,
  `["REQ", <id>, {"kinds": [3871], "#t": ["dp1-<mailbox>", …, "dp1-9576"], "since": now - 604800, "limit": 1}]`
  (`since` a week back so events from phones with a slow clock still match; stored events come from the catch-up).
  When a group's mailboxes change, only that group's links close the subscription and open a new one; the other
  groups' links send nothing, so the change doesn't show on all of a phone's links at the same moment.
- **Catch-up:** then, one mailbox at a time (groups first), `["REQ", <id>, {"kinds": [3871], "#t": ["dp1-<mailbox>"],
  "since": S, "limit": 500}]`, closed after its `EOSE`. When it returns 500 events, whatever they carry (an event
  with no packet in it counts too: anyone can post one under a mailbox's tag), an older page follows with
  `"until"` = the oldest `created_at` received (that value minus 1 if it equals the previous `until`), up to 4 pages
  for a group mailbox and 1 for `9576`. S = the time this phone last caught up that mailbox on that relay minus 25
  minutes (events are dated up to 17 minutes back, and clocks differ), but at most 7 days back. That time, saved on the phone per relay and mailbox, becomes the
  time a catch-up that ended with `EOSE` was started, and then follows the `created_at` of live events (never past
  the phone's clock). A catch-up refused with `CLOSED`, or without `EOSE` within 20 s, moves on and is tried on the
  next link. If the last page of a group mailbox was still full, the mailbox was not read to the end. The phone then
  also saves what it left out: the events from S to the `until` an older page would have had. On each later link, once
  the new events are fetched, it asks for that with `"since"` and `"until"` (never more than 7 days back), with the
  pages that link has left, until a page comes back with fewer than 500 events. For `9576` nothing is saved: its
  newest page is all a phone reads.
- **Publish:** at most one event every 1.5 s, newest packet first, to every connected relay that isn't resting.
  Packets written on this phone (messages and its receipts: the very packets it wrote, see "The same packet", never
  another packet sent under one of their ids) in the last 40 minutes go first, after a random 0–8 s. All
  others (bridging, and older own packets, which may have gone up before the app was killed) wait a random 0–30 s, so
  phones that got the same packet at the same moment don't all find it missing, and are then looked up with
  `["REQ", <id>, {"kinds": [3871], "#x": [<up to 50 packet ids>], "limit": <count>}]`; a packet counts as there only if
  a relay returns an event whose packet has the same `ts` and cipher (the ttl may differ), and only if that event is
  one the packet's recipients get: it has the `t` tag of the packet's own mailbox, no `expiration` earlier than its
  `created_at` + 604800, and a `created_at` no more than 25 minutes before the packet's `ts` (in seconds). Anyone can
  post a copy in an event that no subscription returns: for a packet the phone wrote or got from another peer, such
  a copy counts for nothing, whichever way it arrives, and the phone posts the packet. The others are posted, at
  most one every 4.5 s. A phone's own older packets are looked up before anything it passes on and, if no relay has
  them, go up with its own packets. For the rest, the links that brought them take turns, in the lookups and in
  posting, each link's newest packet first: one link's packets, whatever `ts` they carry, can't take every turn. A packet older than 7 days is never posted (checked again right before posting). A packet
  whose first copy a relay brought never goes back up, whatever event it came in, unless another peer later brings it
  with a higher ttl. This holds after a restart too: a phone remembers which of its packets came from a relay first
  (see "The same packet"), the 1,000 with the newest `ts`, and the 500 it passes on again are its newest, so none of
  those is forgotten. Delivery receipts are sent too.
- **Sent:** a packet counts as on the relays (and, for an own message, as "Sent") only when a relay answers
  `["OK", <event id>, true, …]` (or `false` with a reason starting `duplicate`), or the lookup found it. If every relay
  it went to refuses it, or none accepts it within 30 s, it is queued again, up to 3 tries; after that it waits until
  the relays link again. Each phone remembers up to 3,000 packets it saw accepted.
- **Queues:** at most 500 own packets, 5,000 to look up and 2,000 to post, and at most 8 MiB (encoded packet sizes)
  in each of the last two; when a queue is full, a newer packet
  replaces the oldest one, except that a packet the phone wrote never gives way to one it only passes on, and that
  while the packets one link brought are more than a quarter of a queue, that link's oldest goes first (as in "A full
  queue"). Two packets with one id are two entries.
- **Throttling:** a relay that refuses an event with a reason starting `rate` (`rate-limited:`, `rate limited`),
  `blocked` or `banned` gets no more events from this phone for 10 minutes (it keeps the subscription). With every
  relay resting, packets wait in the queue.
- **Limits on relays:** a relay must answer the WebSocket upgrade with 101 straight away (no 1xx before it). One message
  is at most 1 MiB, and its JSON at most 16 levels deep; a link that sends more is cut or the message ignored. Android
  uses no WebSocket extension, so no message is compressed: an extension a relay names in its answer is not taken up,
  and a frame with a reserved bit set (RSV1 marks a compressed message, whose size says nothing about what it inflates
  to) ends the link. A group's links hold one link to each relay: a second one is never opened beside it, and nothing
  that arrives on a link the phone no longer holds is taken. Each relay gets at most 6,000 messages a minute (`OK`,
  `EOSE` and `CLOSED` aside), across its
  links. Once a link goes over, the phone's saved catch-up time for that relay stops moving, so what was dropped is fetched on the next
  link. An event whose `id` or `pubkey` is not 64 lowercase hex characters is ignored. Relays can't be trusted with
  any of this: when events arrive, their ids and signatures aren't checked, because packets don't rely on them. Only a
  report does (next).
- **Reports:** a reported Public message is hidden on the phone, and a NIP-56 report (kind 1984, tags
  `["e", <event id>, "other"]` and `["p", <author>]`) goes to the relays. It names an event that carried the very
  packet the phone shows (see "The same packet") and that its author signed, never what a relay's frame merely claims.
  For packets of mailbox `9576`, a phone remembers the events whose `id` is the SHA-256 of their own fields (NIP-01)
  and whose `sig` is 128 lowercase hex characters: for each packet, the first such event from each relay, for up to
  2,000 packets. An event that carries another packet under the same packet id is kept apart and never stands in.
  When the report is made, the first remembered event whose signature checks (BIP-340) is named; until there is one,
  the report waits.

### Tor

"Connect through Tor" (on by default) hides the phone's internet address from the relays. Phones are Tor clients
only, never relays. Each group's links use their own SOCKS user name, and Tor keeps different user names on different circuits,
so relays can't tell from the exit address that two groups share a phone.

- **Android:** Tor runs inside the app (tor-android 0.4.9.14, the Tor that Orbot uses) while the switch is on. Every
  relay link and the check below go through its SOCKS port; host names are resolved by Tor. Until it has a circuit,
  nothing goes to the relays.
- **iPhone:** Tor runs inside the app (iCepa's Tor.framework 409.15.3) from the first time it is needed until the app
  quits (Tor can start only once per launch). Relay links and the check use a URL session whose only route is Tor's
  SOCKS port on `localhost`, a free port picked at start.
- **Check:** before linking to any relay, the app fetches `https://check.torproject.org/api/ip` through Tor and goes
  on only if the answer has `"IsTor": true`; otherwise it asks again (every 15 s on Android, 5 s on the iPhone, while
  Tor starts). It asks again whenever all relays are lost.
- **Switching:** turning the relays off, or Tor on or off, ends every relay link made under the old setting, and none
  is made under it afterwards, whatever the app was doing at that moment (a resubscribe after a key change, a
  reconnect).
- Tested 2026-11-06 and 2026-11-09: no connection from either app to a relay or to the check went around Tor.
- **Relays through Tor:** `wss://relay.damus.io`, `wss://relay.primal.net`, `wss://nostr.oxtr.dev`,
  `wss://relay.ditto.pub`, `wss://relay.nostr.net`, `wss://nostr.data.haus`. nos.lol and nostr.mom don't answer Tor
  exits (tested 2026-11-06); the four relays in both lists carry messages between phones with and without Tor.

### Everyone filter

In other people's Public messages and places, each app masks offensive words with bullets (`•`, one per letter)
before showing them. Whole messages are never hidden, so an emergency message with a swear word still gets through.
Your own messages are shown as written. Both apps use the same list (`WordFilter.LIST` / `WordFilter.list`; the tests
pin its SHA-256) and the same rules:

- A word is a run of letters, digits, `@` and `$`; only words with a letter are checked.
- It is compared after removing accents, lowercasing, reading `0 1 3 4 5 7 @ $` as `o i e a s t a s` and dropping
  anything that is not `a`–`z`. Stretched words (a letter 3+ times in a row) are also tried with those runs cut to two
  letters and to one.
- It matches a list entry exactly, or starts with an entry that ends in `*`. Only whole words match, so "Scunthorpe"
  and "puttanesca" stay as written.

### Blocking

Each app lets its user block a sender and hide a message. Both stay on the phone; nothing is sent.

- A block is kept for one channel: the Public channel, or one group (by its group id, which stays when the group's
  code changes). It hides that sender id's messages, places, check-ins and alerts in that channel only. A member id
  means a person only inside its channel, and on the Public channel anyone can write under any id whose key the phone
  doesn't have yet.
- Only the sender of a message whose signature checked can be blocked. A message shown as "not verified yet" can be
  hidden, but not its sender: otherwise someone could get a group member hidden by posting under that member's id.
- A block saved before blocks had a channel still hides that sender id everywhere.

## Wi-Fi networks

Phones on the same Wi-Fi network connect over TCP: a router with no internet, a phone hotspot, a community network or a
long-range rooftop link. Nothing leaves that network.

- **Discovery:** DNS-SD (Bonjour/mDNS), service type `_darkproof._tcp`, instance name = the node id, port = the TCP
  port the device listens on (7777 when it is free). The iPhone lists `_darkproof._tcp` in `NSBonjourServices`.
- **Who connects:** for a discovered device, only the device with the smaller node id connects (comparing bytes), and it
  tries again every 10 s while there is no link. An address the user typed is always connected, and redialed every 5 s.
- **Only the local network:** a discovered device is linked only at an address on the local network: private or
  link-local (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7) or in the same subnet as the phone's
  own address, never loopback (debug builds take it, for tests on a computer), whatever address the DNS-SD answer
  names. A phone takes incoming connections only from such addresses.
  The iPhone's listener and its links to discovered devices never use the mobile network. An address the user typed
  is dialed wherever it is. At most 8 discovered devices are dialed at once, and at most 256 are remembered.
- **Frames:** a 4-byte big-endian length (1–16,064), then the frame. The first frame in each direction is HELLO: the
  ASCII bytes `DPW1`, then the node id in UTF-8 (1–64 bytes). Every later frame is one packet. A link whose first frame
  is not a HELLO, or whose HELLO carries the receiver's own node id, is closed.
- **Restarts and duplicate links:** when a node id that already has a link opens another, the phones keep one:
  - if the two links were dialed by different phones and the older one is less than 10 s old (both dialed at about
    the same time), the link dialed by the smaller node id (comparing bytes) stays, so both phones keep the same one;
  - otherwise the new link replaces the old one, which is closed (after a restart the old one may be dead without
    either side knowing).

  Each link is its own peer for forwarding, so all stored packets go to the new link. A phone doesn't redial a typed
  address while the phone that answered there is linked another way from that address. Node ids prove nothing: a link
  from another address that carries the same node id doesn't count, neither here nor for the 10 s retry of a
  discovered device (on the iPhone, where a discovered device's address isn't known before it is dialed: only a link
  this phone dialed to that device counts).
- **Limits:** at most 32 links, 8 of them from one address (one more takes the place of that address's oldest). At
  most 16 incoming connections wait for their HELLO, 4 of them from one address; one more takes the place of one
  that is still waiting. A frame longer than the limit closes the link, and so does a missing HELLO 10 s after
  connecting. A phone that stops reading is cut when 12 MiB wait for it; when 32 MiB wait for all links together, the
  links with the most waiting are cut, not the one the next packet is for. Turning Wi-Fi networks off closes every
  link and every connection still being set up.
- **iPhone:** the listener and the Bonjour browser start again after any failure (after 2 s, doubling up to 30 s),
  and when the app comes back to the foreground.

## LoRa via MeshCore

MeshCore radios ("companion radio" firmware 1.15 or newer, `FIRMWARE_VER_CODE` ≥ 11) pair with the phone over Bluetooth
LE: Nordic UART service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`, the phone writes (with response) to `6E400002-…` and
gets notifications from `6E400003-…`. One write or notification is one frame of at most 176 bytes; integers are
little-endian. Source: github.com/meshcore-dev/MeshCore, `examples/companion_radio/MyMesh.cpp` (v1.18).

- **Setup:** `16 03` (device query, protocol 3; the reply `0D` carries the firmware version code in byte 1 and the
  number of channel slots in byte 3), then `01` + 7 zero bytes + `DarkProof` (app start; reply `05`).
- **Channel:** DarkProof uses the hashtag channel `#darkproof`, key = the first 16 bytes of SHA-256(`#darkproof`) =
  `835a8265454a846068f204bf5ea81bd3`. The phone reads the slots with `1F <idx>` (reply `12 <idx> <name[32]> <key[16]>`)
  and, if none holds that key, writes it to the first empty slot with `20 <idx> <name[32]> <key[16]>`.
- **Sending:** `3E <idx> FF <type:u16> <packet>`: channel data, flooded (`FF`), data type `0xFF44` (MeshCore's range
  for development; to be registered before release). The packet is ≤ 163 bytes. Reply `00`.
- **Receiving:** the radio pushes `83` when something is queued; the phone sends `0A` until the reply is `0A`. Channel
  data arrives as `1B <snr> 00 00 <idx> <path_len> <type:u16> <len> <packet>`; only `type` `0xFF44` on the DarkProof
  slot is read. Other frames are skipped.
- **Pacing:** as for Meshtastic: only packets from the last 30 minutes, one every 20 s, no delivery receipts. At most
  20 packets wait. The packets a phone wrote itself go first, and one it only passes on never takes the place of one
  of them: when the queue is full, the oldest packet passed on makes room.

## Bluetooth LE

This is the only radio link both platforms share without a router. Android phones also keep Nearby. Duplicate packets arriving over both are dropped by the forwarding rules.

### Roles

Every device is a **peripheral**: it advertises and runs a GATT server. It is also a **central**: it scans and connects.

An iPhone in the background advertises in a way Android cannot see. It can still scan and connect out to Android phones, which always advertise.

### Service UUID

`bec33cd5-a64e-4e1a-b8c1-868db67d0df3`, the same for every DarkProof device (see "Groups and the Public channel").

- **Advertisement:** connectable. It contains only this 128-bit service UUID, with no device name.

### Characteristics

| Name | UUID | Properties | Direction |
|---|---|---|---|
| RX | `33826aba-0bb0-4dfe-9c1a-86e48726ed62` | write (with response) | central → peripheral |
| TX | `94eb2c34-0e66-4bea-864c-d02a7694a3d5` | notify, with a CCCD descriptor | peripheral → central |

### Connection setup

1. The central connects.
2. It requests the largest ATT MTU and discovers the service.
3. It enables notifications on TX by writing `01 00` to its CCCD.
4. The link is then open in both directions.

### Frames

Each direction carries a stream of frames:

`type` (1 byte) ‖ `length` (2 bytes, big-endian) ‖ `payload` (`length` bytes).

**Chunking:**
- The stream is cut into chunks of at most min(ATT_MTU − 3, 512) bytes. 512 is the longest attribute value BLE allows; Android throws an error above it.
- Each chunk is one write (central → peripheral) or one notification (peripheral → central). Long or prepared writes are not used.
- A side sends its next chunk only after the previous one completes. For the central, that is the write response. For the peripheral, that is the notification-sent callback.

**Frame types:**

| Type | Name | Payload |
|---|---|---|
| 1 | HELLO | Version byte `1`, then the node id: printable ASCII, 1–64 bytes. |
| 2 | PACKET | One packet (see above), up to 18 + 16000 bytes. |

- Each side sends HELLO as its first frame, as soon as it can send. For the central, that is after its CCCD write completes. For the peripheral, that is when notifications get enabled.
- PACKET frames received before the other side's HELLO are ignored.
- An unknown type, a version other than 1, or a bad length means: disconnect.
- After both HELLOs, each side treats the link as a neighbor in the forwarding rules. That includes sending it all stored packets.

### Restarts

An app can restart while the Bluetooth connection stays up: iOS keeps an app's connections when it closes the app to
free memory, and relaunches the app in the background when Bluetooth activity arrives. The restarted app starts the
link over and sends HELLO again.
- A device that gets a HELLO on a link that already has one **from the same node id** answers with its own HELLO again,
  and sends the link all stored packets again (the receiver drops the ones it has), each at most once a minute per
  link, and the stored packets only when the link's send queue is empty. The first repeat is always answered. This
  avoids HELLO ping-pong and endless re-sending. A HELLO with a different node id means: disconnect.
- A central whose peer republishes the DarkProof service (the service is invalidated) disconnects and connects again.

### Duplicate links

Two devices may connect to each other twice, once in each direction. When a device sees a second link to a node id it already has a link with, it keeps only one:
- Keep the link whose central has the smaller node id, comparing bytes.
- If both links have the same central, keep the newer one.

Both sides reach the same decision. Either side may close the other link.

This is for two links made at the same moment (less than 10 s apart). After that, the link that is there stays: a
device closes a later link that it dialed itself, or that reached it over the same radio connection. A later link
that the other side dialed over another connection is not closed for its node id: it runs beside the first, since it
may be the real phone and the first one someone who only says its id. If both lead to one phone, that phone closes
the link it dialed.

A GATT client opened on an existing connection only to read signal strength for Finder is no link (see "Finder", F7).

### Limits

- At most 6 links per device. At the limit, a new link takes the place of the oldest link whose other side hasn't sent
  HELLO after 5 s, if there is one; otherwise of a link at random, at most once in 30 s (see "Node ids prove
  nothing"), and else it is refused.
- A link whose other side sends no HELLO within 10 s of the link opening (central: notifications enabled; peripheral:
  the central subscribed) is closed, and so is a central link still being set up 30 s after dialing. An iPhone cannot
  disconnect a central: it stops serving it, and the link no longer counts toward the limit.
- Each link's send queue holds at most 1 MiB and 1,000 frames. The stored packets go to a new link oldest first and
  only as far as the queue has room (983,040 bytes and 500 packets); the rest, and every later packet in its turn,
  follow as frames leave the queue, so a store larger than the queue is passed on whole. Should the queue still run
  over, the oldest PACKET frames not yet started are dropped (the neighbour gets stored packets again on its next
  link); HELLO frames are never dropped.

## History

- `0xB1`, v0.2: text fields used Java's modified UTF-8, and the code was not normalized.
- `0xB2`, v0.3: standard UTF-8, NFC-normalized group code.
- `0xB2`, v0.4: same packets. Discovery no longer depends on the group code (one Bluetooth LE service UUID, one Nearby service id, no Multipeer group filter), and the Public channel was added. v0.3 and v0.4 devices do not find each other.
- `0xB3`, v0.5: scrypt instead of PBKDF2 for the group key; member ids instead of the device id in `sender`, with a
  member key per group that signs every packet, introductions and person emoji; link ids that change at every app start. Every key, mailbox and fingerprint changes; group codes stay the same. v0.4 and
  v0.5 phones link but drop each other's packets. On its first start, a v0.5 app deletes the packets it stored
  before: nobody can read them any more, so they would only be passed on as noise.
- `0xB3`, v0.6: same packets, new rules (see "Doors and epochs"): the code only opens the door, messages use random
  epoch secrets, introductions also carry an encryption key, and members can be removed. v0.5 phones can't read v0.6
  group messages.
- `0xB3`, 2026-11-09: padding, a link per group, generated codes only, 16 person emoji, weekly encryption keys, ML-KEM
  and new keys split over packets. New keys are types `0x06` and `0x07`; phones on an earlier build can't read them.
  Later the same day: ML-KEM required (iOS 26 or later), joining needs an admin, Tor on by default with a circuit per group,
  blurred times, and a key per message. Then: a packet's position has to be its sender's own, and invites name the admins
  who can let a phone in.
- `0xB3`, 2026-11-13: a message sealed with a message key signs its position and number too, under the tag
  `darkproof-sig-mk-v1`. Phones on an earlier build and on this one drop each other's group messages (they open, the
  signature doesn't check); receipts, introductions, new keys and the Public channel are unchanged. Also new rules,
  with the same bytes: only members write in an epoch, nothing new is shown from someone removed, and someone removed
  adds no keys.
- `0xB3`, 2026-11-22: replies and reactions (a 5th plaintext field in group messages, see "Replies and reactions")
  and journey notes in receipts (see "Journey notes"). Nothing else in a packet changes. A phone on an earlier build
  shows a reply as an ordinary message and a reaction as a short message of its own, and skips the notes.
- `0xB3`, 2026-11-22, later: a timer for disappearing messages, carried in a 5th field of new keys (see
  "Disappearing messages"). A phone on an earlier build takes those keys unchanged and keeps messages one to two
  weeks.
- `0xB3`, 2026-11-22, later still: the leave note, a group message with the 5th field `05 01` (see "Leaving the
  group"); admins' phones then remove the member with the normal removal. A phone on an earlier build shows it as the
  ordinary message `👋 I left the group`.
- `0xB3`, 2026-11-22, last: check-in clocks, a group message with the 5th field `0x04` and a deadline (see "If I go
  quiet"); the group's phones keep the clock and tell their owners if no check-in reaches them in time. A phone on an
  earlier build shows it as the ordinary message `⏳ Check on me if I don't check in within 1 hour`.
- `0xB3`, 2026-11-23: the finder note, the 5th field `0x06` and the sender's link id in "needs help" check-ins (see
  "Finder"). A phone on an earlier build ignores the field and shows the check-in as before.

## Apple peer-to-peer (iPhone to iPhone)

iPhones also connect to each other with MultipeerConnectivity, which uses Bluetooth and Apple's peer-to-peer Wi-Fi. Android cannot join these connections.

- **Service type:** `blackout-mesh`. It is listed in the app's `NSBonjourServices`.
- **Peer display name:** the node id.
- **Discovery info:** `v` = `1`. Peers without it are ignored.
- **Who invites:** the device with the smaller node id. The invitation context is the UTF-8 bytes of `blackout-mesh-v1`, and invitations with a different context are declined.
- **Messages:** each packet is sent as one `.reliable` message.
