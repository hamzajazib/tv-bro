//the page of the TV in the local network, see index.html and LocalSendServer
(function () {
    'use strict';
    var strings = document.body.dataset;
    var params = new URLSearchParams(location.hash.substring(1));
    var key = params.get('k') || sessionStorage.getItem('tvbroKey') || '';
    if (key) sessionStorage.setItem('tvbroKey', key);
    //the key stays out of the address bar
    if (location.hash) history.replaceState(null, '', location.pathname);
    var from = params.get('from') || describeDevice();

    function describeDevice() {
        var ua = navigator.userAgent;
        var browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /SamsungBrowser/.test(ua) ? 'Samsung Internet' :
            /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : '';
        var os = /Android/.test(ua) ? 'Android' : /iPhone|iPad|iPod/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' :
            /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
        return [browser, os].filter(Boolean).join(' · ');
    }

    function format(template, values) {
        return template.replace(/%(\d)\$[sd]/g, function (match, n) { return values[n - 1]; });
    }

    function show(element, text, kind) {
        element.textContent = text;
        element.className = 'status' + (kind ? ' ' + kind : '');
    }

    function errorText(status, code) {
        if (status === 403) return strings.badKey;
        switch (code) {
            case 'need_permission': return strings.needPermission;
            case 'storage': return strings.storage;
            case 'cancelled': return strings.cancelled;
            default: return strings.failed;
        }
    }

    function errorCode(text) {
        try { return JSON.parse(text).error; } catch (e) { return null; }
    }

    var textForm = document.getElementById('textForm');
    var textInput = document.getElementById('text');
    var sendButton = document.getElementById('send');
    var textStatus = document.getElementById('textStatus');
    textForm.addEventListener('submit', function (event) {
        event.preventDefault();
        var text = textInput.value.trim();
        if (!text) return;
        sendButton.disabled = true;
        fetch('text', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-TVBro-Key': key },
            body: JSON.stringify({ text: text, from: from })
        }).then(function (response) {
            return response.text().then(function (body) {
                if (response.ok) {
                    textInput.value = '';
                    show(textStatus, strings.sent, 'ok');
                } else {
                    show(textStatus, errorText(response.status, errorCode(body)), 'error');
                }
            });
        }).catch(function () {
            show(textStatus, strings.offline, 'error');
        }).then(function () {
            sendButton.disabled = false;
        });
    });

    var filesInput = document.getElementById('files');
    var progress = document.getElementById('progress');
    var fileStatus = document.getElementById('fileStatus');
    filesInput.addEventListener('change', function () {
        var files = Array.prototype.slice.call(filesInput.files);
        filesInput.value = '';
        sendFiles(files);
    });

    //one after another: the TV saves each one before it answers
    function sendFiles(files) {
        if (!files.length) return;
        var file = files.shift();
        var xhr = new XMLHttpRequest();
        var query = 'name=' + encodeURIComponent(file.name) + '&type=' + encodeURIComponent(file.type || '') +
            '&from=' + encodeURIComponent(from);
        xhr.open('PUT', 'file?' + query);
        xhr.setRequestHeader('X-TVBro-Key', key);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        progress.hidden = false;
        progress.value = 0;
        show(fileStatus, format(strings.sending, [file.name, '0%']));
        xhr.upload.onprogress = function (event) {
            if (!event.lengthComputable) return;
            var percent = Math.floor(event.loaded * 100 / event.total);
            progress.value = percent;
            show(fileStatus, format(strings.sending, [file.name, percent + '%']));
        };
        xhr.onload = function () {
            progress.hidden = true;
            if (xhr.status === 200) {
                var name = file.name;
                try { name = JSON.parse(xhr.responseText).name || name; } catch (e) {}
                show(fileStatus, format(strings.saved, [name]), 'ok');
                sendFiles(files);
            } else {
                show(fileStatus, errorText(xhr.status, errorCode(xhr.responseText)), 'error');
            }
        };
        xhr.onerror = function () {
            progress.hidden = true;
            show(fileStatus, strings.offline, 'error');
        };
        xhr.send(file);
    }
})();
