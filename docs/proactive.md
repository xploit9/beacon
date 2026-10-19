# Proactive assistant

When enabled, Cypher adds four fixed jobs to the selected Hermes profile: a morning brief (08:00), a calendar watch every 30 minutes from 07:00 to 22:00, an evening follow-up sweep (19:00), and nightly memory upkeep (02:00). Times use the profile's Hermes timezone. Routine checks stay quiet; useful results send a short task notice to the phone, and opening it in Cypher shows the full text in the owner's assistant conversation.

## Enable and check status

The authenticated owner and profile can read `GET /api/plugins/cypher/mobile/proactive`. It returns `enabled`, the four managed jobs, and service status. Enable or disable with `PUT /api/plugins/cypher/mobile/proactive` and a JSON body containing only `{"enabled": true}` or `{"enabled": false}`. For installations with multiple profiles, select the profile using the `profile` query parameter.

Disabling removes only Cypher's managed jobs and persona section. The plain-language `PROACTIVE_PREFERENCES.md` file is kept.

## Safety

The morning, calendar-watch and evening jobs run unattended with the Google Workspace skill, which needs Hermes's terminal tool. Mail and calendar text is untrusted: anyone can put text in the inbox or calendar. The job prompts treat it as data, never instructions, and Hermes's default `approvals.cron_mode: deny` refuses commands it classifies as dangerous, but other terminal commands can still run without the owner present. Keep `cron_mode` at `deny`, grant the skill only the scopes it needs, and leave Proactive off on a profile whose terminal can reach anything you would not let an email trigger.

## Connect services

The status response lists Gmail, Google Calendar, Drive, Contacts, Sheets, and Docs, with an instruction for each service the bundled Google Workspace skill supports. Setup happens on the Hermes host using that profile's Google Workspace skill; grant only the scopes needed. No direct OAuth link is available through this API.

While either existing Cypher urgent-mail monitor remains active, the proactive morning, calendar-watch and evening jobs check Calendar only. After retiring both monitors, turn proactive off and on to include Gmail in those jobs, so the same inbox is not scanned by two schedules.
