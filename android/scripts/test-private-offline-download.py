import os,subprocess,uuid,re,json
from pathlib import Path
adb=str(Path(os.environ['READINGAPP_SDK_ROOT'])/'platform-tools'/'adb')
serial=os.environ['ANDROID_SERIAL'];expected=os.environ['READINGAPP_EXPECTED_AVD'];package='dev.francisgiann.readingapp.dev'
base=Path(__file__).resolve().parent.parent/'.readingapp-cache'/'offline-qa';base.mkdir(parents=True,exist_ok=True);nonce=str(uuid.uuid4())
assert serial.startswith('emulator-') and expected
def raw(*args,timeout=25):return subprocess.check_output([adb,'-s',serial,*args],timeout=timeout)
def guard():
 assert raw('emu','avd','name').decode().splitlines()[0]==expected
 assert raw('shell','getprop','ro.kernel.qemu').strip()==b'1'
def a(*args,timeout=25):guard();return raw(*args,timeout=timeout)
def run(method):
 guard();print('Running',method,flush=True)
 r=raw('shell','am','instrument','-w','-r','-e','class','mihon.readingapp.downloads.OriginalOfflineDownloadTest#'+method,'-e','arkhive_fixture_nonce',nonce,package+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=100).decode()
 (base/('offline-'+method+'.log')).write_text(r)
 assert 'OK (1 test)' in r and 'FAILURES!!!' not in r and 'INSTRUMENTATION_FAILED' not in r,r[-6500:]
 print('Passed',method,flush=True)
airplane=a('shell','settings','get','global','airplane_mode_on').decode().strip()
wifi=a('shell','settings','get','global','wifi_on').decode().strip()
assert airplane=='0' and wifi=='1','Start this explicit test with normal emulator connectivity.'
try:
 guard()
 exists=subprocess.run([adb,'-s',serial,'shell','run-as',package,'test','-f','cache/original-offline-download-test.json'],capture_output=True).returncode==0
 if exists:
  previous=json.loads(a('exec-out','run-as',package,'cat','cache/original-offline-download-test.json'))
  nonce=previous['nonce'];uuid.UUID(nonce)
  print('Cleaning only the previous original fixture before the fresh regression.',flush=True)
  run('removeOnlyThisOriginalFixture')
  nonce=str(uuid.uuid4())
 run('queueOriginalChapterWithoutStarting')
 persisted=a('exec-out','run-as',package,'cat','shared_prefs/active_downloads.xml').decode();assert '<string name=' in persisted
 a('shell','am','force-stop',package)
 run('restoredQueueDownloadsOriginalImages')
 a('shell','am','force-stop',package)
 a('shell','cmd','connectivity','airplane-mode','enable');a('shell','svc','wifi','disable')
 assert a('shell','settings','get','global','airplane_mode_on').strip()==b'1'
 assert a('shell','settings','get','global','wifi_on').strip()==b'0'
 run('downloadedChapterDecodesAfterRestartInAirplaneMode')
 run('removeOnlyThisOriginalFixture')
 (base/'offline-download-result.json').write_text(json.dumps({'passed':True,'avd':expected,'originalFixtureNonce':nonce,'queueSurvivedForceStop':True,'nativeDownloaderCompleted':True,'downloadedLoaderTotalPages':6,'visiblePageDecoded':True,'noActiveNetwork':True,'airplaneMode':True,'wifiDisabled':True,'fixtureCleaned':True},indent=2))
finally:
 a('shell','cmd','connectivity','airplane-mode','disable');a('shell','svc','wifi','enable')
 print('Restored dedicated emulator connectivity.',flush=True)
