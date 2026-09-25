# GardenSociety

GardenSociety provides persistent NPC residents for The Garden SMP.

## Core rules

- Vanilla villagers remain vanilla traders until adopted by a registered Territory.
- Society residents do not use vanilla trading.
- A resident displays only its persistent name above its head.
- Shift-right-click a resident for its character card.
- Outside immigration requires at least **3 vacant NPC-eligible Homes/Apartments** and **2 vacant job positions assigned to that same Territory**.
- Immigration claims one home and one Trade position.
- Society births inherit their parents' Territory membership.
- Resident death releases its Trade position and removes its Territory membership.

## Test commands

- `/society housing <property-uuid> on`
- `/society adopt <territory>` while looking at a villager
- `/society info` while looking at a villager
- `/society population <territory>`
- `/society immigrate <territory>` to run the immigration gate immediately

Workplaces must be assigned to a Territory with:
`/business workplace territory <business> <workplace> <territory>`.
