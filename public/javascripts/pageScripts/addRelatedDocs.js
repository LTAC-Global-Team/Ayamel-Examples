$(function(){
    var ackeys = [];

    Object.keys(Ayamel.utils.p1map).forEach(function(p1){
        var code = Ayamel.utils.p1map[p1],
            engname = Ayamel.utils.getLangName(code,"eng").toLowerCase(),
            localname = Ayamel.utils.getLangName(code,code).toLowerCase();
        ackeys.push({key: engname, value: code});
        if(localname!==engname){
            ackeys.push({key: localname, value: code});
        }
    });

    var acroot = AhoCorasick.buildTable(ackeys);

    var viewCapR, addCapR,
        addAnnR, viewAnnR,
        langList, altered = false,
        courseQuery = courseId ? "?course=" + courseId : "",
        addCapTemplate = '<table class="table table-bordered">\
            <thead><tr>\
                <th>File</th><th>Track name</th><th>Language</th><th>Kind</th>\
            </tr></thead>\
            <tbody>\
                <tr>\
                <td><input type="file" value="{{files}}" on-change="addfile" /></td>\
                <td><input type="text" value="{{label}}" /></td>\
                <td><SuperSelect icon="icon-globe" text="Select Language" value="{{lang}}" btnpos="left" multiple="false" options="{{languages}}" modal="{{modalId}}" defaultOption="{{defaultOption}}"></td>\
                <td><select value="{{kind}}">\
                    <option value="subtitles">Subtitles</option>\
                    <option value="captions">Captions</option>\
                    <option value="descriptions">Descriptions</option>\
                    <option value="chapters">Chapters</option>\
                    <option value="metadata">Metadata</option>\
                </select></td>\
                <td><button on-tap="upload" id="uplCaptionsBtn">Upload</button></td>\
                </tr>\
            </tbody>\
        </table>',
        addAnnTemplate = '<table class="table table-bordered">\
            <thead><tr>\
                <th>File</th><th>Track name</th><th>Language</th>\
            </tr></thead>\
            <tbody>\
                <tr>\
                <td><input type="file" value="{{files}}" /></td>\
                <td><input type="text" value="{{label}}" /></td>\
                <td><SuperSelect icon="icon-globe" text="Select Language" value="{{lang}}" btnpos="left" multiple="false" options="{{languages}}" modal="{{modalId}}" defaultOption="{{defaultOption}}"></td>\
                <td><button on-tap="upload">Upload</button></td>\
                </tr>\
            </tbody>\
        </table>',
        captionsTemplate = '<table class="table table-bordered">\
            <thead><tr>\
                <th>Track name</th><th>Language</th><th>Download</th><th>Options</th>\
            </tr></thead>\
            <tbody>\
                {{#resources:i}}<tr>\
                    <td>{{.title}}</td>\
                    <td>{{getLanguage(this)}}</td>\
                    <td>{{#.content.files}}<a href="{{.downloadUri}}" download="{{calcName(title, .mime)}}">{{.mime}}&nbsp;</a>{{/.content.files}}</td>\
                    <td>\
                        <button class="btn btn-magenta" on-tap="delete:{{.id}}"><i class="icon-trash"></i> Delete</button>\
                    </td>\
                </tr>{{/resources}}\
            </tbody>\
        </table>',
        annotationsTemplate = '<table class="table table-bordered pad-top-high">\
            <thead><tr>\
                <th>Track name</th><th>Language</th><th>Download</th><th>Options</th>\
            </tr></thead>\
            <tbody>\
                {{#resources}}<tr>\
                    <td>{{.title}}</td>\
                    <td>{{getLanguage(this)}}</td>\
                    <td>{{#.content.files}}<a href="{{.downloadUri}}" download="{{calcName(title, .mime)}}">{{.mime}}&nbsp;</a>{{/.content.files}}</td>\
                    <td>\
                        <button class="btn btn-magenta" on-tap="delete:{{.id}}"><i class="icon-trash"></i> Delete</button>\
                    </td>\
                </tr>{{/resources}}\
            </tbody>\
        </table>';

    /*langList = Object.keys(Ayamel.utils.p1map).map(function (p1) {
        var code = Ayamel.utils.p1map[p1],
            engname = Ayamel.utils.getLangName(code,"eng"),
            localname = Ayamel.utils.getLangName(code,code);
        return {value: code, text: engname, desc: localname!==engname?localname:void 0};
    });

    langList.push({ value: "apc", text: "North Levantine Arabic"});
    langList.push({ value: "arz", text: "Egyptian Arabic"});*/

    langList = Object.keys(Ayamel.utils.langCodes).map(function(code){
        var engname = Ayamel.utils.getLangName(code,"eng"),
            localname = Ayamel.utils.getLangName(code,code);
        return {value: code, text: engname, desc: localname!==engname?localname:void 0};
    });
    langList.sort(function(a,b){ return a.text.localeCompare(b.text); });

    function getLanguage(resource) {
        var langs = resource.languages.iso639_3;
        return (langs && langs[0])?Ayamel.utils.getLangName(langs[0]):"English";
    }

    function calcName(title, mime){
        try { return TimedText.addExt(mime, title); }
        catch (_){ return title; }
    }

    // A resource id -> Resource object function
    function getResources(ids) {
        return Promise.all(ids.map(function(id){
            return ResourceLibrary.load(id);
        }));
    }

    function deleteDoc(rid, type, resource) {
        if(!confirm("Are you sure you want to delete?")){ return false; }

        $.ajax("/content/" + content.id + "/delete/" + rid + courseQuery, {
            type: "post",
            cache: false,
            contentType: false,
            processData: false,
            success: function(data) {
                var resourceArr;
                if(type === "subtitles") {
                    resourceArr = viewCapR.get('resources');
                } else if (type === "annotations") {
                    resourceArr = viewAnnR.get('resources');
                }
                resourceArr.splice(resourceArr.indexOf(resource),1);
                altered = true;
                alert("File Deleted.");
            },
            error: function(data) {
                console.log(data);
                alert("There was a problem deleting the document.");
            }
        });
    }

    viewCapR = new Ractive({
        el: "#captionsTable",
        template: captionsTemplate,
        data: {
            resources: [],
            getLanguage: getLanguage,
            calcName: calcName
        }
    });

    viewCapR.on('delete', function(_, which){ deleteDoc(which, "subtitles", _.context); });

    addCapR = new Ractive({
        el: "#captionsUpload",
        template: addCapTemplate,
        data: {
            label: '',
            kind: 'subtitles',
            lang: [],
            languages: langList,
            modalId: 'captionTrackModal',
            defaultOption: {value:'zxx',text:'No Linguistic Content'}
        }
    });

    addCapR.on("addfile", function(){
        var lang, files = this.get('files'),
            file = files[0],
            name = file.name,
            mime = file.type || TimedText.inferType(name);

        this.set('label', TimedText.removeExt(mime,name));

        lang = acroot.findall(name.toLowerCase()).map(function(r){ return r.value; })[0];
        this.set('lang',lang?[lang]:[]);
    });

    addCapR.on('upload', function(){
        var data, file, mime, dup = "",
            files = this.get('files'),
            label = this.get('label');

        if(!(files && label)){
            alert('File & Name are Required');
            return;
        }

        document.getElementById("uplCaptionsBtn").disabled = true;

        file = files[0];
        mime = file.type || TimedText.inferType(file.name);

        if(!mime){
            alert('Could not determine file type.');
            document.getElementById("uplCaptionsBtn").disabled = false;
            return;
        }

        // Check that caption file to be uploaded is supported.
        // It would be nice to actually restrict the file dialog
        // so only supported files could be selected.
        if(!TimedText.isSupported(mime)){
            alert('Files of type \"'+mime+'\" are not supported.');
            return;
        }

        viewCapR.get('resources').forEach(function(r){
            if(r.title === label){ dup = r.id; }
        });

        if(dup !== "" && !confirm("Replace existing document with same name?")){
            document.getElementById("uplCaptionsBtn").disabled = false;
            return;
        }

        //TODO: Validate the file
        data = new FormData();
        data.append("file", new Blob([file],{type:mime}), file.name);
        data.append("label", label);
        data.append("language", this.get('lang'));
        data.append("kind", this.get('kind'));
        data.append("contentId", content.id);
        data.append("resourceId", dup);
        return $.ajax({
            url: "/captionaider/save",
            data: data,
            cache: false,
            contentType: false,
            processData: false,
            type: "post",
            dataType: "text"
        }).then(function(data){
            //TODO: save a roundtrip by having this ajax call return the complete updated resource
            ResourceLibrary.load(data).then(function(resource){
                viewCapR.get('resources').push(resource);
                altered = true;
                alert("Captions saved.");
            });

            document.getElementById("uplCaptionsBtn").disabled = false;

        },function(xhr, status, error){
            alert("Error occurred while saving\n"+status)
            document.getElementById("uplCaptionsBtn").disabled = false;
        });
    });

    viewAnnR = new Ractive({
        el: "#annotationsTable",
        template: annotationsTemplate,
        data: {
            resources: [],
            getLanguage: getLanguage,
            calcName: function(title){ return title+'.json'; }
        }
    });

    viewAnnR.on('delete', function(_, which){ deleteDoc(which, "annotations", _.context); });

    addAnnR = new Ractive({
        el: "#annotationsUpload",
        template: addAnnTemplate,
        data: {
            label: '',
            lang: [],
            languages: langList,
            modalId: 'annotationsModal',
            defaultOption: {value:'zxx', text:'No Linguistic Content'}
        }
    });
    addAnnR.on('upload', function(){
        var reader, file, data,
            files = this.get('files'),
            label = this.get('label');
        if(!(files && label)){
            alert('File & Name are Required');
            return;
        }

        data = new FormData();
        data.append("file", new Blob([files[0]],{type:'application/json'}), label);
        data.append("title", label);
        data.append("language", addAnnR.get('lang'));
        data.append("contentId", content.id);

        $.ajax("/annotations/save", {
            type: "post",
            data: data,
            cache: false,
            contentType: false,
            processData: false
        }).then(function (data) {
            ResourceLibrary.load(data).then(function(resource){
                viewAnnR.get('resources').push(resource);
                altered = true;
                alert("Annotations saved.");
            });
        },function(data) {
            console.log(data);
            alert("There was a problem while saving the annotations.");
        });
    });

    // If files were uploaded or deleted, reload the page when the user closes the modal
    function refresh(){
        if(altered){ document.location.reload(); }
    }
    $('#captionTrackModal').on("hidden", refresh);
    $('#annotationsModal').on("hidden", refresh);

    ResourceLibrary.load(content.resourceId, function(resource){
        var captionTrackIds = resource.relations
                .filter(function(r){return r.type==="transcript_of";})
                .map(function(r){return r.subjectId;}).join(','),
            annotationIds = resource.relations
                .filter(function(r){return r.type==="references";})
                .map(function(r){return r.subjectId;}).join(',');

        if(captionTrackIds.length){
            $.ajax("/ajax/permissionChecker", {
                type: "post",
                data: {
                    contentId: content.id,
                    permission: "edit",
                    documentType: "captionTrack",
                    ids: captionTrackIds
                }
            }).then(function(data){
                getResources(data).then(function(rs){ viewCapR.set('resources', rs); });
            });
        }

        if(annotationIds.length){
            $.ajax("/ajax/permissionChecker", {
                type: "post",
                data: {
                    contentId: content.id,
                    permission: "edit",
                    documentType: "annotationDocument",
                    ids: annotationIds
                }
            }).then(function(data) {
                getResources(data).then(function(rs){ viewAnnR.set('resources', rs); });
            });
        }
    });
});