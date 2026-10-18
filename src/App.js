import React, {memo, useEffect, useState} from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  StyleSheet, Alert, NativeModules, ScrollView
} from 'react-native';

const {SipBridge} = NativeModules;

const Field = memo(function Field({
  label,
  value,
  onChangeText,
  secure,
  keyboardType,
}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        blurOnSubmit={false}
        keyboardType={keyboardType || 'default'}
        placeholderTextColor="#555"
        placeholder={label}
      />
    </View>
  );
});

export default function App() {
  const [host, setHost] = useState('');
  const [port, setPort] = useState('5060');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [bridgeExtension, setBridgeExtension] = useState('');
  const [answerRings, setAnswerRings] = useState('1');
  const [loaded, setLoaded] = useState(false);
  const [status, setStatus] = useState(null);

  useEffect(() => {
    SipBridge.getConfig()
      .then(cfg => {
        if (cfg.configured) {
          setHost(cfg.host);
          setPort(String(cfg.port));
          setUsername(cfg.username);
          setPassword(cfg.password);
          setBridgeExtension(cfg.bridgeExtension);
          setAnswerRings(String(cfg.answerRings));
        }
      })
      .catch(e => Alert.alert('Error', 'Could not load config: ' + e.message))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    let active = true;
    const poll = () => {
      SipBridge.getStatus()
        .then(s => active && setStatus(s))
        .catch(() => {});
    };
    poll();
    const timer = setInterval(poll, 2000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, []);

  const saveAndStart = async () => {
    try {
      const result = await SipBridge.saveConfig({
        host: host.trim(),
        port: parseInt(port, 10) || 5060,
        username: username.trim(),
        password,
        bridgeExtension: bridgeExtension.trim(),
        answerRings: parseInt(answerRings, 10) || 1,
      });
      Alert.alert('Saved', result);
    } catch (e) {
      Alert.alert('Error', e.message);
    }
  };

  const stateColor = !status
    ? '#88aaff'
    : status.state === 'REGISTERED'
      ? '#4ade80'
      : status.state === 'REGISTERING'
        ? '#facc15'
        : '#f87171';

  return (
    <ScrollView
      style={styles.container}
      keyboardShouldPersistTaps="always"
      keyboardDismissMode="none"
    >
      <Text style={styles.title}>GSM SIP Gateway</Text>
      <View style={styles.statusBar}>
        <Text style={[styles.statusText, {color: stateColor}]}>
          SIP: {status ? status.state : 'Loading...'}
        </Text>
        {status && status.detail ? (
          <Text style={styles.statusDetail}>{status.detail}</Text>
        ) : null}
        {status ? (
          <Text style={styles.statusDetail}>
            SIP API: {status.sipApiSupported ? 'yes' : 'NO'} | VoIP:{' '}
            {status.sipVoipSupported ? 'yes' : 'NO'} | Wi-Fi only:{' '}
            {status.sipWifiOnly ? 'yes' : 'no'} | USE_SIP:{' '}
            {status.useSipGranted ? 'granted' : 'DENIED'}
          </Text>
        ) : null}
      </View>
      <Text style={styles.section}>FreePBX Settings</Text>
      <Field
        label="FreePBX IP"
        value={host}
        onChangeText={setHost}
        keyboardType="numbers-and-punctuation"
      />
      <Field
        label="SIP Port"
        value={port}
        onChangeText={setPort}
        keyboardType="numeric"
      />
      <Field
        label="SIP Username"
        value={username}
        onChangeText={setUsername}
      />
      <Field
        label="SIP Password"
        value={password}
        onChangeText={setPassword}
        secure
      />
      <Field
        label="Bridge Target (extension or SIP URI)"
        value={bridgeExtension}
        onChangeText={setBridgeExtension}
      />
      <Field
        label="Answer After Rings"
        value={answerRings}
        onChangeText={setAnswerRings}
        keyboardType="numeric"
      />
      <TouchableOpacity
        style={[styles.btn, !loaded && styles.btnDisabled]}
        onPress={saveAndStart}
        disabled={!loaded}
      >
        <Text style={styles.btnText}>
          {loaded ? 'Save & Start Gateway' : 'Loading config...'}
        </Text>
      </TouchableOpacity>
      <View style={styles.howto}>
        <Text style={styles.howtoTitle}>How it works</Text>
        <Text style={styles.howtoItem}>1. Incoming call arrives on SIM</Text>
        <Text style={styles.howtoItem}>2. App auto-answers the GSM call</Text>
        <Text style={styles.howtoItem}>3. Bridges audio to FreePBX via SIP</Text>
        <Text style={styles.howtoItem}>4. FreePBX routes the call normally</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#0a0a0a', padding: 20},
  title: {color: '#fff', fontSize: 22, fontWeight: 'bold', marginTop: 50, marginBottom: 6},
  statusBar: {backgroundColor: '#1a1a2e', padding: 10, borderRadius: 8, marginBottom: 24},
  statusText: {color: '#88aaff', fontSize: 13, fontWeight: '700'},
  statusDetail: {color: '#9ca3af', fontSize: 12, marginTop: 4},
  section: {color: '#888', fontSize: 12, textTransform: 'uppercase', letterSpacing: 1, marginBottom: 12},
  field: {marginBottom: 14},
  label: {color: '#aaa', marginBottom: 5, fontSize: 12},
  input: {backgroundColor: '#1e1e1e', color: '#fff', padding: 12, borderRadius: 8, borderWidth: 1, borderColor: '#2a2a2a'},
  btn: {backgroundColor: '#2563eb', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 8, marginBottom: 24},
  btnDisabled: {backgroundColor: '#374151'},
  btnText: {color: '#fff', fontSize: 15, fontWeight: '700'},
  howto: {backgroundColor: '#111827', padding: 16, borderRadius: 10, marginBottom: 40},
  howtoTitle: {color: '#fff', fontWeight: '600', marginBottom: 10},
  howtoItem: {color: '#6b7280', marginBottom: 6, fontSize: 13},
});
