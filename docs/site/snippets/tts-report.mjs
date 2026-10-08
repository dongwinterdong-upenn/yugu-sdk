import { YuguClient } from '@shengzhiai/yugu-web-sdk';

const client = new YuguClient({ appKey: process.env.YUGU_APP_KEY, secretKey: process.env.YUGU_SECRET_KEY });
// [START tts]
const tts = await client.tts({ text: '你好世界', language: 'zh-CN', voice: 'xiaoyan', format: 'mp3' });
console.log('示范音', tts.absoluteUrl, '时长', tts.duration);
// [END tts]
// [START report]
const report = await client.getReport(process.argv[2]);
console.log('点评', report.report?.summary, '维度分', report.report?.dimensionScores);
// [END report]
await client.close();
