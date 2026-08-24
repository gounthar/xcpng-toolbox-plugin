<!--
Keep this short. The reasoning belongs in the commit message body, which is read for years;
this is read once. Anything the reviewer has to DO goes in the first two lines.
-->

## What this changes

## How it was verified

<!--
Be specific and be honest about the gaps. One of:
  - ran it: the command and what it printed
  - read the artifact: the file and line that says so
  - neither: say so here, in these words, rather than softening it into confident prose

If it touches anything Toolbox renders, say whether you installed it and looked. Tests passing
is not that check: pull request #6 was correct in every artifact and wrong on screen.
-->

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] `.github/scripts/check-tooling-references.sh` exits 0
- [ ] If it changes anything on screen, I installed it and looked at it
- [ ] If it changes a message the user reads, I have that message's wording written down
