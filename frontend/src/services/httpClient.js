import axios from 'axios'
import { environment } from '../config/environment.js'

const httpClient = axios.create({
  baseURL: environment.apiBaseUrl,
  timeout: 15_000,
  headers: {
    Accept: 'application/json',
  },
})

export default httpClient
