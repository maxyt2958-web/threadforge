/*
 * Copyright 2026 ThreadForge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(() => {
  'use strict';

  const dialog = document.getElementById('p2p-share-dialog');
  if (!dialog) return;

  const topbarBtn = document.getElementById('p2p-share-btn');
  const closeBtn = document.getElementById('p2p-close-btn');
  const tabSend = document.getElementById('p2p-tab-send');
  const tabReceive = document.getElementById('p2p-tab-receive');
  const panelSend = document.getElementById('p2p-panel-send');
  const panelReceive = document.getElementById('p2p-panel-receive');
  const statusBanner = document.getElementById('p2p-status-banner');
  const networkInfo = document.getElementById('p2p-network-info');
  const sizeHint = document.getElementById('p2p-size-hint');

  const dropzone = document.getElementById('p2p-dropzone');
  const pickBtn = document.getElementById('p2p-pick-file-btn');
  const pickFolderBtn = document.getElementById('p2p-pick-folder-btn');
  const fileInput = document.getElementById('p2p-file-input');
  const folderInput = document.getElementById('p2p-folder-input');
  const uploadWrap = document.getElementById('p2p-upload-wrap');
  const uploadLabel = document.getElementById('p2p-upload-label');
  const uploadPct = document.getElementById('p2p-upload-pct');
  const uploadProgress = document.getElementById('p2p-upload-progress');
  const mySharesList = document.getElementById('p2p-my-shares');
  const mySharesEmpty = document.getElementById('p2p-my-shares-empty');

  let uploadBusy = false;
  if (pickFolderBtn && folderInput && !('webkitdirectory' in folderInput)) {
    pickFolderBtn.hidden = true;
  }

  const keyForm = document.getElementById('p2p-key-form');
  const keyInput = document.getElementById('p2p-key-input');
  const keySubmit = document.getElementById('p2p-key-submit');
  const receiveWrap = document.getElementById('p2p-receive-wrap');
  const receiveLabel = document.getElementById('p2p-receive-label');
  const lanList = document.getElementById('p2p-lan-list');
  const lanEmpty = document.getElementById('p2p-lan-empty');
  const refreshBtn = document.getElementById('p2p-refresh-btn');

  let pollTimer = null;
  let maxFileBytes = 536870912;

  const csrfHeaders = () => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    const h = {};
    if (token && header) h[header] = token;
    return h;
  };

  const formatBytes = (bytes) => {
    if (typeof bytes !== 'number' || Number.isNaN(bytes) || bytes < 0) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  };

  const setStatus = (msg, kind = '') => {
    if (!msg) {
      statusBanner.hidden = true;
      statusBanner.textContent = '';
      statusBanner.className = 'p2p-status';
      return;
    }
    statusBanner.hidden = false;
    statusBanner.textContent = msg;
    statusBanner.className = 'p2p-status' + (kind ? ` is-${kind}` : '');
  };

  const selectTab = (mode) => {
    const isSend = mode === 'send';
    tabSend.classList.toggle('is-active', isSend);
    tabReceive.classList.toggle('is-active', !isSend);
    tabSend.setAttribute('aria-selected', String(isSend));
    tabReceive.setAttribute('aria-selected', String(!isSend));
    panelSend.hidden = !isSend;
    panelReceive.hidden = isSend;
    setStatus('');
  };

  const openDialog = (mode = 'send') => {
    selectTab(mode);
    if (!dialog.open) {
      dialog.showModal();
    }
    refreshState();
    if (!pollTimer) {
      pollTimer = window.setInterval(refreshState, 2000);
    }
  };

  const stopPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer);
      pollTimer = null;
    }
  };

  if (!('closedBy' in HTMLDialogElement.prototype)) {
    const outside = (e) => {
      const r = dialog.getBoundingClientRect();
      return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
    };
    let pressedOutside = false;
    dialog.addEventListener('pointerdown', (e) => { pressedOutside = outside(e); });
    dialog.addEventListener('pointerup', (e) => {
      if (pressedOutside && outside(e)) dialog.close();
    });
  }

  dialog.addEventListener('close', () => {
    dialog.returnValue = '';
    stopPolling();
  });

  if (topbarBtn) {
    topbarBtn.addEventListener('click', () => openDialog('send'));
  }

  document.addEventListener('click', (e) => {
    const trigger = e.target.closest('[data-p2p-open]');
    if (trigger) {
      e.preventDefault();
      openDialog('send');
    }
  });

  if (closeBtn) {
    closeBtn.addEventListener('click', () => dialog.close());
  }

  tabSend.addEventListener('click', () => selectTab('send'));
  tabReceive.addEventListener('click', () => selectTab('receive'));
  refreshBtn.addEventListener('click', () => refreshState());

  const parseFilenameFromDisposition = (headerValue, fallback) => {
    if (!headerValue) return fallback;
    const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(headerValue);
    if (utf8) {
      try {
        return decodeURIComponent(utf8[1]);
      } catch (_) {}
    }
    const basic = /filename="?([^";]+)"?/i.exec(headerValue);
    return basic ? basic[1] : fallback;
  };

  const triggerDownload = async (url, label) => {
    setStatus('');
    receiveWrap.hidden = false;
    receiveLabel.textContent = label || 'Connecting to peer and verifying SHA-256…';
    keySubmit.disabled = true;
    try {
      const res = await fetch(url, { credentials: 'same-origin' });
      if (!res.ok) {
        let errText = `Transfer failed (${res.status})`;
        try {
          const errJson = await res.json();
          if (errJson?.message) errText = errJson.message;
        } catch (_) {}
        setStatus(errText, 'error');
        return;
      }
      const blob = await res.blob();
      const filename = parseFilenameFromDisposition(
        res.headers.get('Content-Disposition'),
        'p2p-download'
      );
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(blobUrl), 10000);
      setStatus(`Received "${filename}" (${formatBytes(blob.size)}) with verified checksum.`, 'ok');
      refreshState();
    } catch (err) {
      setStatus(`P2P receive failed: ${err?.message || 'network error'}`, 'error');
    } finally {
      receiveWrap.hidden = true;
      keySubmit.disabled = false;
    }
  };

  const setPickersDisabled = (disabled) => {
    uploadBusy = disabled;
    if (pickBtn) pickBtn.disabled = disabled;
    if (pickFolderBtn) pickFolderBtn.disabled = disabled;
  };

  const shareFile = (file) => {
    if (!file || uploadBusy) return;
    if (file.size <= 0) {
      setStatus('Cannot share an empty file.', 'error');
      return;
    }
    if (maxFileBytes > 0 && file.size > maxFileBytes) {
      setStatus(`File exceeds max P2P share size (${formatBytes(maxFileBytes)}).`, 'error');
      return;
    }
    setStatus('');
    setPickersDisabled(true);
    uploadWrap.hidden = false;
    uploadLabel.textContent = `Staging "${file.name}" for LAN peers…`;
    uploadPct.textContent = '0%';
    uploadProgress.value = 0;

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/p2p/shares');
    const headers = csrfHeaders();
    Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]));
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
    xhr.setRequestHeader('X-Upload-Filename', encodeURIComponent(file.name));

    xhr.upload.onprogress = (ev) => {
      if (ev.lengthComputable && ev.total > 0) {
        const pct = Math.min(100, Math.round((ev.loaded * 100) / ev.total));
        uploadProgress.value = pct;
        uploadPct.textContent = `${pct}%`;
      }
    };

    xhr.onload = () => {
      uploadWrap.hidden = true;
      setPickersDisabled(false);
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const share = JSON.parse(xhr.responseText);
          setStatus(
            `Sharing "${share.fileName}" on LAN — Peer Key: ${share.peerKey}`,
            'ok'
          );
        } catch (_) {
          setStatus('P2P share started.', 'ok');
        }
        refreshState();
      } else {
        let msg = `Could not start share (${xhr.status})`;
        try {
          const err = JSON.parse(xhr.responseText);
          if (err?.message) msg = err.message;
        } catch (_) {}
        setStatus(msg, 'error');
      }
    };

    xhr.onerror = () => {
      uploadWrap.hidden = true;
      setPickersDisabled(false);
      setStatus('Network error while staging P2P share.', 'error');
    };

    xhr.send(file);
  };

  const uploadOneStagedFile = (sessionId, file, relativePath, onProgress) =>
    new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/api/p2p/shares/sessions/${encodeURIComponent(sessionId)}/files`);
      const headers = csrfHeaders();
      Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]));
      xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
      xhr.setRequestHeader('X-Upload-Filename', encodeURIComponent(relativePath));

      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) onProgress(ev.loaded);
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress(file.size);
          resolve();
        } else {
          let msg = `Upload failed (${xhr.status})`;
          try {
            const err = JSON.parse(xhr.responseText);
            if (err?.message) msg = err.message;
          } catch (_) {}
          reject(new Error(msg));
        }
      };

      xhr.onerror = () => reject(new Error('Network error while uploading file.'));
      xhr.send(file);
    });

  const shareBatch = async (entries, { folder = false, name = 'shared-files' } = {}) => {
    if (uploadBusy) return;
    if (!entries || entries.length === 0) {
      setStatus(folder ? 'Selected folder contains no files.' : 'No files selected.', 'error');
      return;
    }
    if (!folder && entries.length === 1 && !entries[0].relativePath.includes('/')) {
      shareFile(entries[0].file);
      return;
    }
    const totalBytes = entries.reduce((sum, e) => sum + (e.file?.size || 0), 0);
    if (totalBytes <= 0) {
      setStatus('Cannot share empty files or an empty folder.', 'error');
      return;
    }
    if (maxFileBytes > 0 && totalBytes > maxFileBytes) {
      setStatus(
        `Selection (${formatBytes(totalBytes)}) exceeds max P2P share size (${formatBytes(maxFileBytes)}).`,
        'error'
      );
      return;
    }

    setStatus('');
    setPickersDisabled(true);
    uploadWrap.hidden = false;
    uploadProgress.value = 0;
    uploadPct.textContent = '0%';
    uploadLabel.textContent = folder
      ? `Preparing folder "${name}" (${entries.length} files)…`
      : `Preparing ${entries.length} files…`;

    let sessionId = null;
    try {
      const startRes = await fetch(
        `/api/p2p/shares/sessions?name=${encodeURIComponent(name)}&folder=${folder ? 'true' : 'false'}`,
        {
          method: 'POST',
          headers: csrfHeaders(),
          credentials: 'same-origin'
        }
      );
      if (!startRes.ok) {
        let msg = `Could not start share session (${startRes.status})`;
        try {
          const err = await startRes.json();
          if (err?.message) msg = err.message;
        } catch (_) {}
        throw new Error(msg);
      }
      const session = await startRes.json();
      sessionId = session.sessionId;

      let uploadedBefore = 0;
      for (let i = 0; i < entries.length; i++) {
        const { file, relativePath } = entries[i];
        uploadLabel.textContent = `Staging ${i + 1}/${entries.length}: ${relativePath}`;
        await uploadOneStagedFile(sessionId, file, relativePath, (loaded) => {
          const pct = Math.min(99, Math.round(((uploadedBefore + loaded) * 100) / totalBytes));
          uploadProgress.value = pct;
          uploadPct.textContent = `${pct}%`;
        });
        uploadedBefore += file.size;
      }

      uploadLabel.textContent = folder ? `Packing "${name}.zip" for LAN peers…` : 'Starting LAN P2P share…';
      uploadProgress.value = 100;
      uploadPct.textContent = '100%';

      const finRes = await fetch(
        `/api/p2p/shares/sessions/${encodeURIComponent(sessionId)}/finalize`,
        {
          method: 'POST',
          headers: csrfHeaders(),
          credentials: 'same-origin'
        }
      );
      if (!finRes.ok) {
        let msg = `Could not finalize share (${finRes.status})`;
        try {
          const err = await finRes.json();
          if (err?.message) msg = err.message;
        } catch (_) {}
        throw new Error(msg);
      }
      sessionId = null;
      const share = await finRes.json();
      setStatus(
        `Sharing "${share.fileName}" (${formatBytes(share.fileSize)}) on LAN — Peer Key: ${share.peerKey}`,
        'ok'
      );
      refreshState();
    } catch (err) {
      if (sessionId) {
        fetch(`/api/p2p/shares/sessions/${encodeURIComponent(sessionId)}`, {
          method: 'DELETE',
          headers: csrfHeaders(),
          credentials: 'same-origin'
        }).catch(() => {});
      }
      setStatus(err?.message || 'Could not stage files for P2P sharing.', 'error');
    } finally {
      uploadWrap.hidden = true;
      setPickersDisabled(false);
    }
  };

  const readDirectoryEntries = (reader) =>
    new Promise((resolve, reject) => {
      const all = [];
      const next = () => {
        reader.readEntries(
          (batch) => {
            if (!batch || batch.length === 0) {
              resolve(all);
            } else {
              all.push(...batch);
              next();
            }
          },
          reject
        );
      };
      next();
    });

  const collectFromFsEntry = async (entry, prefix = '') => {
    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
      return [{ file, relativePath: prefix ? `${prefix}/${entry.name}` : entry.name }];
    }
    if (entry.isDirectory) {
      const dirPrefix = prefix ? `${prefix}/${entry.name}` : entry.name;
      const children = await readDirectoryEntries(entry.createReader());
      const out = [];
      for (const child of children) {
        const nested = await collectFromFsEntry(child, dirPrefix);
        out.push(...nested);
      }
      return out;
    }
    return [];
  };

  pickBtn.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => {
    const files = Array.from(fileInput.files || []);
    fileInput.value = '';
    if (files.length === 1) {
      shareFile(files[0]);
    } else if (files.length > 1) {
      const entries = files.map((f) => ({ file: f, relativePath: f.name }));
      shareBatch(entries, { folder: false, name: 'shared-files' });
    }
  });

  if (pickFolderBtn && folderInput) {
    pickFolderBtn.addEventListener('click', () => folderInput.click());
    folderInput.addEventListener('change', () => {
      const files = Array.from(folderInput.files || []);
      folderInput.value = '';
      if (files.length === 0) return;
      const firstRel = files[0].webkitRelativePath || '';
      const folderName = firstRel.includes('/') ? firstRel.split('/')[0] : 'shared-folder';
      const entries = files.map((f) => ({
        file: f,
        relativePath: f.webkitRelativePath || `${folderName}/${f.name}`
      }));
      shareBatch(entries, { folder: true, name: folderName });
    });
  }

  dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('is-dragover');
  });
  dropzone.addEventListener('dragleave', () => {
    dropzone.classList.remove('is-dragover');
  });
  dropzone.addEventListener('drop', async (e) => {
    e.preventDefault();
    dropzone.classList.remove('is-dragover');
    const items = Array.from(e.dataTransfer?.items || []);
    const fsEntries = items
      .map((item) => (typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null))
      .filter(Boolean);

    if (fsEntries.length > 0) {
      const hasDirectory = fsEntries.some((entry) => entry.isDirectory);
      if (hasDirectory) {
        const collected = [];
        for (const entry of fsEntries) {
          collected.push(...(await collectFromFsEntry(entry)));
        }
        const folderName = fsEntries.length === 1 && fsEntries[0].isDirectory
          ? fsEntries[0].name
          : 'shared-folder';
        shareBatch(collected, { folder: true, name: folderName });
        return;
      }
    }

    const files = Array.from(e.dataTransfer?.files || []);
    if (files.length === 1) {
      shareFile(files[0]);
    } else if (files.length > 1) {
      const entries = files.map((f) => ({ file: f, relativePath: f.name }));
      shareBatch(entries, { folder: false, name: 'shared-files' });
    }
  });

  keyForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const target = keyInput.value.trim();
    if (!target) return;
    triggerDownload(
      `/api/p2p/receive?target=${encodeURIComponent(target)}`,
      `Connecting to ${target}…`
    );
  });

  const renderMyShares = (items) => {
    mySharesList.replaceChildren();
    mySharesEmpty.hidden = items.length > 0;
    for (const s of items) {
      const li = document.createElement('li');
      li.className = 'p2p-card';

      const top = document.createElement('div');
      top.className = 'p2p-card-top';
      const title = document.createElement('span');
      title.className = 'p2p-card-title';
      title.textContent = `${s.fileName} (${formatBytes(s.fileSize)})`;
      const badge = document.createElement('span');
      badge.className = 'p2p-peer-badge';
      badge.textContent = s.peerKey;
      top.append(title, badge);

      const meta = document.createElement('div');
      meta.className = 'p2p-card-meta';
      meta.textContent = s.status === 'TRANSFERRING'
        ? `Transferring (${s.progressPercent}%) · ${s.host}:${s.port}`
        : s.status === 'WAITING'
          ? `Waiting for peer on ${s.host}:${s.port}`
          : s.errorMessage ? `${s.status}: ${s.errorMessage}` : s.status;

      li.append(top, meta);

      if (s.status === 'TRANSFERRING' || s.status === 'WAITING') {
        if (s.status === 'TRANSFERRING') {
          const bar = document.createElement('progress');
          bar.className = 'p2p-progress';
          bar.max = 100;
          bar.value = s.progressPercent;
          li.append(bar);
        }

        const actions = document.createElement('div');
        actions.className = 'p2p-card-actions';

        const copyBtn = document.createElement('button');
        copyBtn.type = 'button';
        copyBtn.className = 'p2p-secondary-btn';
        copyBtn.textContent = 'Copy Key';
        copyBtn.addEventListener('click', async () => {
          try {
            await navigator.clipboard.writeText(s.peerKey);
            setStatus(`Copied Peer Key ${s.peerKey} to clipboard.`, 'ok');
          } catch (_) {
            setStatus(`Peer Key: ${s.peerKey}`, 'ok');
          }
        });
        actions.append(copyBtn);

        const composerInput = document.getElementById('composer-input');
        if (composerInput) {
          const insertBtn = document.createElement('button');
          insertBtn.type = 'button';
          insertBtn.className = 'p2p-secondary-btn';
          insertBtn.textContent = 'Insert in chat';
          insertBtn.addEventListener('click', () => {
            const note = `P2P share **${s.fileName}** (${formatBytes(s.fileSize)}) — Peer Key: \`${s.peerKey}\``;
            composerInput.value = composerInput.value ? `${composerInput.value}\n${note}` : note;
            dialog.close();
            composerInput.focus();
          });
          actions.append(insertBtn);
        }

        const cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'p2p-danger-btn';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', async () => {
          await fetch(`/api/p2p/shares/${encodeURIComponent(s.id)}`, {
            method: 'DELETE',
            headers: csrfHeaders(),
            credentials: 'same-origin'
          });
          refreshState();
        });
        actions.append(cancelBtn);
        li.append(actions);
      }

      mySharesList.append(li);
    }
  };

  const renderLanList = (lanShares, discoveredPeers) => {
    lanList.replaceChildren();
    const total = lanShares.length + discoveredPeers.length;
    lanEmpty.hidden = total > 0;

    for (const s of lanShares) {
      const li = document.createElement('li');
      li.className = 'p2p-card';

      const top = document.createElement('div');
      top.className = 'p2p-card-top';
      const title = document.createElement('span');
      title.className = 'p2p-card-title';
      title.textContent = `${s.fileName} (${formatBytes(s.fileSize)})`;
      const badge = document.createElement('span');
      badge.className = 'p2p-peer-badge';
      badge.textContent = s.peerKey;
      top.append(title, badge);

      const meta = document.createElement('div');
      meta.className = 'p2p-card-meta';
      meta.textContent = `From ${s.ownerDisplayName} · ${s.host}:${s.port}`;

      const actions = document.createElement('div');
      actions.className = 'p2p-card-actions';
      const dlBtn = document.createElement('button');
      dlBtn.type = 'button';
      dlBtn.className = 'p2p-primary-btn';
      dlBtn.textContent = 'Download via P2P';
      dlBtn.disabled = s.status !== 'WAITING';
      dlBtn.addEventListener('click', () => {
        triggerDownload(
          `/api/p2p/shares/${encodeURIComponent(s.id)}/download`,
          `Downloading "${s.fileName}" from ${s.ownerDisplayName}…`
        );
      });
      actions.append(dlBtn);

      li.append(top, meta, actions);
      lanList.append(li);
    }

    for (const p of discoveredPeers) {
      const li = document.createElement('li');
      li.className = 'p2p-card';

      const fileDesc = (p.files || [])
        .map((f) => `${f.fileName} (${formatBytes(f.fileSize)})`)
        .join(', ') || 'Shared file(s)';

      const top = document.createElement('div');
      top.className = 'p2p-card-top';
      const title = document.createElement('span');
      title.className = 'p2p-card-title';
      title.textContent = fileDesc;
      const badge = document.createElement('span');
      badge.className = 'p2p-peer-badge';
      badge.textContent = p.peerKey;
      top.append(title, badge);

      const meta = document.createElement('div');
      meta.className = 'p2p-card-meta';
      meta.textContent = `LAN Device: ${p.deviceName || p.host} · ${p.host}:${p.port}`;

      const actions = document.createElement('div');
      actions.className = 'p2p-card-actions';
      const dlBtn = document.createElement('button');
      dlBtn.type = 'button';
      dlBtn.className = 'p2p-primary-btn';
      dlBtn.textContent = 'Receive';
      dlBtn.addEventListener('click', () => {
        triggerDownload(
          `/api/p2p/receive?target=${encodeURIComponent(`${p.host}:${p.port}`)}`,
          `Receiving from ${p.deviceName || p.host}…`
        );
      });
      actions.append(dlBtn);

      li.append(top, meta, actions);
      lanList.append(li);
    }
  };

  async function refreshState() {
    try {
      const res = await fetch('/api/p2p/state', { credentials: 'same-origin' });
      if (!res.ok) return;
      const data = await res.json();
      if (typeof data.maxFileBytes === 'number') {
        maxFileBytes = data.maxFileBytes;
        sizeHint.textContent = `Max ${formatBytes(maxFileBytes)} · Broadcasts via Fandem (Android, Desktop, CLI, or browser peers).`;
      }
      if (data.localAddress) {
        networkInfo.textContent = `Local node: ${data.localAddress} · Multicast discovery ${data.multicastDiscoveryActive ? 'active' : 'by peer key'}`;
      }
      renderMyShares(data.myShares || []);
      renderLanList(data.lanShares || [], data.discoveredPeers || []);
    } catch (_) {}
  }
})();
