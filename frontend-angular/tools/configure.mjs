import fs from 'node:fs';
const names={apiBase:'P360_API_BASE_URL',clientId:'P360_ENTRA_CLIENT_ID',tenantId:'P360_ENTRA_TENANT_ID',redirectUri:'P360_ENTRA_REDIRECT_URI',scope:'P360_ENTRA_API_SCOPE'};
const config=Object.fromEntries(Object.entries(names).map(([key,name])=>{const value=process.env[name];if(!value)throw new Error(`Falta configuración pública ${name}`);return [key,value];}));
config.checkoutMode=process.env.P360_CHECKOUT_MODE||'HTTP';
if(!['HTTP','RABBITMQ'].includes(config.checkoutMode))throw new Error('Modo de checkout inválido');
fs.mkdirSync('public',{recursive:true});fs.writeFileSync('public/config.json',JSON.stringify(config));
console.log('Configuración pública escrita; sin secretos.');
