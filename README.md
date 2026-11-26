# Clue Trail

A real-world scavenger hunt app for Android. One person builds a trail of clues and
locations, sends a 6-character code, and the player follows the clues on their own phone.

## How it works
- **Host:** name the hunt, choose Easy or Hard, choose how close counts as arriving
  (20 ft by default), write each clue and mark its spot (stand there, or pick on a map), publish.
- **Player:** enter the code, go to the starting location, tap **Am I at the right location?**
  - Hard: "You're not there."
  - Easy: also shows how far away they are, never the direction.
  - Within range: the next clue appears. After the last one: "Congratulations, you win!"

Clue locations stay on the server; the app only sends the player's position and gets back yes/no.

## Getting the app onto a phone
Every push to `main` builds the app on GitHub. Open the repo's **Releases** page on the phone,
download `ClueTrail.apk` from the newest build, and open it to install
(Android will ask once to allow installs from your browser).
