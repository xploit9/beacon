"""A file the agent delivers (MEDIA:/path) reaches the owner by its name outside the chat: notifications and a call's hand-over answer."""
import time

from test_mobile import harness, load_private  # noqa: F401  (fixture)
from test_voice_continuation import continuation, intent, admit, close, row  # noqa: F401  (fixture and helpers)


def test_tags_are_read_with_hermes_grammar(harness):
    m = harness[0].mobile
    paths = lambda text: [path for path, _, _ in m.media_tags(text)]
    # Cases from Hermes' tests/gateway/test_media_tag_cleanup.py and test_media_tag_separator.py, as the phone reads them.
    assert paths('MEDIA:/tmp/早报.pdf（782.6 KB）') == ['/tmp/早报.pdf']
    assert paths('MEDIA:/tmp/a.png MEDIA:/tmp/b.png') == ['/tmp/a.png', '/tmp/b.png']
    assert paths("MEDIA:'/tmp/stop!.md'") == ['/tmp/stop!.md']
    assert paths('MEDIA:... and MEDIA:word') == []
    assert paths('media:/tmp/x.pdf') == ['/tmp/x.pdf']
    assert m.named_files('Here: MEDIA:C:\\Users\\me\\My Files\\report q3.pdf.') == 'Here: report q3.pdf.'
    # Prose is not a tag: the bare form needs capitals, as on Hermes' desktop.
    assert m.named_files('Social media: example.com is down') == 'Social media: example.com is down'
    assert m.named_files('MEDIA:/tmp/a.pdf\nMEDIA:/tmp/b_c.csv', mark=m.FILES_MARK) == '📎 a.pdf, b_c.csv'


def test_reply_notification_names_the_file(harness):
    api = harness[0]
    assert api.reply_preview([{'role': 'assistant', 'text': 'Done.\n\nMEDIA:/home/me/out/my_report.pdf'}]) == 'Done. my_report.pdf'
    assert api.reply_preview([{'role': 'assistant', 'text': '**MEDIA:/home/me/out/q3 plan.docx**'}]) == '📎 q3 plan.docx'
    assert api.reply_preview([{'role': 'assistant', 'text': 'No *files* or __marks__ here.'}]) == 'No files or marks here.'
    # Seen live on the demo: the words around a file kept no underscores ("recvqanote.txt").
    assert api.reply_preview([{'role': 'assistant', 'text': "Here's recv_qa_note.txt again.\n\nMEDIA:C:/Users/me/recv-qa/recv_qa_note.txt"}]) \
        == "Here's recv_qa_note.txt again. recv_qa_note.txt"


def test_scheduled_post_notification_names_the_file(harness):
    api = harness[0]
    row = {'id': 'e1', 'state': 'settled', 'expires': time.time() + 60, 'kind': 'task_result', 'conversation': 'c1', 'profile': 'default',
           'activity_key': '', 'reason': 'Morning brief ready: MEDIA:/home/me/briefs/brief_16.pdf'}
    wire = api.notice_wire(row, 'owner')
    assert wire['body'] == wire['preview']['body'] == 'Morning brief ready: brief_16.pdf'
    # A post without files keeps its own lines and words.
    assert api.notice_wire({**row, 'reason': 'Line one\nLine_two  *as written*'}, 'owner')['body'] == 'Line one\nLine_two  *as written*'


def test_a_calls_handover_answer_names_the_file(continuation):
    from hermes_state_runtime import claim_session_input, settle_session_input
    c = continuation
    intent(c, callback=False)
    admit(c)
    close(c)
    db, epoch = c[3:5]
    claimed = claim_session_input(db, epoch=epoch, session_id='native-stored')
    settle_session_input(db, epoch=epoch, admission_id=claimed['admission_id'], generation=claimed['generation'], outcome='completed',
        result={'result': {'final_response': 'Your report is ready: MEDIA:/home/me/out/report.pdf', 'completed': True}, 'usage': {}})
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'completed'
    # What the phone hands the call's model: the job as the call reads it.
    jobs = c[1].get(f'/api/plugins/beacon/mobile/calls/{c[-1]}').json()['jobs']
    assert jobs[0]['result'] == 'Your report is ready: report.pdf (sent to the chat)'
